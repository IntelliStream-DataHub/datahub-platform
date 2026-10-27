// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.config.AgentProperties;
import ai.intellistream.datahub.flow.engine.FlowRecord;
import ai.intellistream.datahub.flow.engine.FlowRecord.Action;
import ai.intellistream.datahub.flow.engine.RunResult;
import ai.intellistream.datahub.flow.fake.FakeMcp;
import ai.intellistream.datahub.flow.processors.Harness.Captured;
import ai.intellistream.datahub.flow.store.AgentTranscripts;
import ai.intellistream.datahub.flow.store.AgentTranscripts.Row;
import ai.intellistream.datahub.llm.LlmBlock;
import ai.intellistream.datahub.llm.LlmBlock.ToolUse;
import ai.intellistream.datahub.llm.LlmToolDef;
import ai.intellistream.datahub.llm.LlmTurn;
import ai.intellistream.datahub.llm.StubLlmClient;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantLlm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.intellistream.datahub.flow.processors.Harness.MINUTE;
import static ai.intellistream.datahub.flow.processors.Harness.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent processor in the engine, with a scripted model, loopback MCP servers advertising hints
 * as the platform's do, and a transcript kept in memory: what the model is offered, what is refused
 * before it reaches a server, how a run ends, and what the second attempt does.
 */
class AgentTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-01T11:05:00Z");
    private static final List<EventRow> EVENTS = List.of(
            new EventRow("pump_21:alarm", Instant.ofEpochMilli(T0), "active", Map.of("severity", "high")),
            new EventRow("pump_22:alarm", Instant.ofEpochMilli(T0 + MINUTE), "active", Map.of()));

    private final Harness h = new Harness();
    private final StubLlmClient llm = new StubLlmClient();
    private final FakeMcp api = new FakeMcp("datahub-api");
    private final List<FakeMcp> servers = new ArrayList<>(List.of(api));
    private final MemoryTranscripts transcripts = new MemoryTranscripts();
    private final AgentProperties properties = new AgentProperties();
    private final TenantConfigService tenants = mock(TenantConfigService.class);
    private final Tenant tenant = new Tenant();

    AgentTest() throws IOException {
        api.tool("event_get", true, false);
        api.tool("event_create", false, false);
        api.tool("event_delete", false, true);
        api.tool("resource_get", true, false);
        api.tool("thing_do", null, null);
        TenantLlm model = new TenantLlm();
        model.setProvider(LlmProvider.OPENAI_COMPATIBLE);
        model.setModel("qwen");
        model.setBaseUrl("http://127.0.0.1:1/v1");
        tenant.setLlm(model);
        when(tenants.getConfig("t")).thenReturn(tenant);
        TenantContext.setTenantId("t");
    }

    @AfterEach
    void close() {
        TenantContext.clear();
        servers.forEach(FakeMcp::close);
        h.close();
    }

    private static ToolUse call(String id, String name, Map<String, Object> args) {
        return new ToolUse(id, name, args);
    }

    private static ToolUse finish(String outcome, String summary) {
        return call("f", Agent.FINISH, Map.of("outcome", outcome, "summary", summary));
    }

    private Agent agent() {
        List<StatelessMcpClient> clients = servers.stream()
                .map(s -> new StatelessMcpClient(s.name, s.url(), HttpClient.newHttpClient(), JSON)).toList();
        return new Agent(settings -> llm, clients, transcripts, properties, tenants,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** {@code in → p (agent) → done | attention | failure}, with every outcome collected. */
    private RunResult run(String properties) {
        h.extra.add(agent());
        h.events(Map.of(), EVENTS);
        return h.pipe("agent", properties, Agent.DONE, Agent.ATTENTION, Agent.FAILURE);
    }

    private static final String DEFAULTS = """
            { "instructions": "Look at every event and say whether a person is needed.", "tools": ["event_*"] }""";

    @Test
    void theModelGetsTheEventsAndTheAllowedToolsAndFinishesWithDone() {
        llm.thenToolCalls(call("c1", "event_get", Map.of("externalIds", List.of("pump_21:alarm"))))
                .thenToolCalls(finish("done", "Both alarms are known and handled."));

        run(DEFAULTS);

        assertThat(llm.firstSent().tools()).extracting(LlmToolDef::name)
                .as("by pattern, a destructive one only by name, plus finish")
                .containsExactly("event_get", "event_create", Agent.FINISH);
        assertThat(llm.firstSent().systemPrompt()).contains("Look at every event").contains("never instructions")
                .contains("2026-10-01T10:00:00Z to 2026-10-01T11:00:00Z");
        assertThat(llm.firstSent().messages().getFirst().text()).startsWith("2 event(s) in the window")
                .contains("\"externalId\":\"pump_21:alarm\"").contains("\"severity\":\"high\"")
                .contains("Use event_get");
        assertThat(api.calls).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("event_get");
            assertThat(c.bearer()).isEqualTo("Bearer harness-token");
        });
        Captured done = h.at(Agent.DONE).getFirst();
        assertThat(done.attribute("agent.outcome")).isEqualTo("done");
        assertThat(done.attribute("agent.summary")).isEqualTo("Both alarms are known and handled.");
        assertThat(done.attribute("agent.iterations")).isEqualTo("2");
        assertThat(done.attribute("agent.toolCalls")).isEqualTo("1");
        assertThat(done.rows()).isEqualTo(EVENTS);
        assertThat(h.at(Agent.ATTENTION)).isEmpty();
        assertThat(records(Action.RECEIVE)).extracting(FlowRecord::transitUri).containsExactly("mcp://datahub-api/event_get");
        assertThat(transcripts.all()).extracting(Row::kind).containsExactly(
                "system", "user", "usage", "tool_call", "tool_result", "usage", "tool_call", "tool_result");
        assertThat(transcripts.all().get(3).payload().get("mutating").asBoolean()).isFalse();
    }

    @Test
    void aToolOffTheListOrDestructiveIsRefusedBeforeItReachesAServer() {
        llm.thenToolCalls(call("c1", "event_delete", Map.of("id", 1)), call("c2", "resource_get", Map.of()))
                .thenToolCalls(finish("done", "Nothing I could do."));

        run(DEFAULTS);

        assertThat(api.calls).isEmpty();
        List<LlmBlock> results = llm.sent().get(1).messages().get(2).blocks();
        assertThat(results).allSatisfy(r -> assertThat(((LlmBlock.ToolResult) r).isError()).isTrue());
        assertThat(((LlmBlock.ToolResult) results.getFirst()).content()).contains("not available to this agent");
        assertThat(h.at(Agent.DONE)).hasSize(1);
    }

    @Test
    void aDestructiveToolIsOfferedOnlyWhenNamedExactly() {
        llm.thenToolCalls(call("c1", "event_delete", Map.of("id", 1)))
                .thenToolCalls(finish("done", "Deleted the stale alarm."));

        run("""
                { "instructions": "Delete the stale alarm.", "tools": ["event_*", "event_delete"] }""");

        assertThat(llm.firstSent().tools()).extracting(LlmToolDef::name)
                .containsExactly("event_get", "event_create", "event_delete", Agent.FINISH);
        assertThat(api.calls).extracting(FakeMcp.Call::name).containsExactly("event_delete");
        assertThat(transcripts.all().get(3).payload().get("mutating").asBoolean()).isTrue();
    }

    @Test
    void aToolWithoutHintsCountsAsMutatingAndAPreviewRefusesItButRunsReads() {
        h.extra.add(agent());
        h.events(Map.of(), EVENTS);
        llm.thenToolCalls(call("c1", "event_get", Map.of()), call("c2", "thing_do", Map.of("x", 1)))
                .thenToolCalls(finish("attention", "Pump 21 needs a look."));

        RunResult result = h.run("""
                { "id": "p", "type": "agent", "properties": { "instructions": "i", "tools": ["*"] } },
                { "id": "done", "type": "test.collect" }, { "id": "attention", "type": "test.collect" }""",
                """
                { "from": "in", "relationship": "success", "to": "p" },
                { "from": "p", "relationship": "done", "to": "done" },
                { "from": "p", "relationship": "attention", "to": "attention" }""", true, null, null);

        assertThat(result.error()).isNull();
        assertThat(llm.firstSent().systemPrompt()).contains("dry run");
        assertThat(api.calls).extracting(FakeMcp.Call::name).containsExactly("event_get");
        LlmBlock.ToolResult refused = (LlmBlock.ToolResult) llm.sent().get(1).messages().get(2).blocks().get(1);
        assertThat(refused.isError()).isTrue();
        assertThat(refused.content()).contains("preview: not executed");
        assertThat(h.at(Agent.DONE)).singleElement().satisfies(d ->
                assertThat(d.attribute("agent.outcome")).isEqualTo("attention"));
        Captured attention = h.at(Agent.ATTENTION).getFirst();
        assertThat(attention.rows()).singleElement().satisfies(row -> {
            assertThat(row.externalId()).startsWith("agent:t:");
            assertThat(row.eventTime()).isEqualTo(NOW);
            assertThat(row.status()).isEqualTo("open");
            assertThat(row.metadata()).containsEntry("summary", "Pump 21 needs a look.").containsEntry("flow", "t");
        });
        assertThat(attention.attribute("agent.summary")).as("the child inherits its parent's attributes")
                .isEqualTo("Pump 21 needs a look.");
        assertThat(records(Action.SEND)).as("nothing was written").isEmpty();
    }

    @Test
    void eventCreateIsStampedWithTheAgentAsItsSource() {
        llm.thenToolCalls(call("c1", "event_create", Map.of("externalId", "note_1", "type", "note", "source", "sap")))
                .thenToolCalls(finish("done", "Noted."));

        run(DEFAULTS);

        assertThat(api.calls).singleElement().satisfies(c -> assertThat(c.args()).containsEntry("source", "agent:t"));
        assertThat(records(Action.SEND)).extracting(FlowRecord::transitUri).containsExactly("mcp://datahub-api/event_create");
        assertThat(transcripts.all().get(3).payload().get("mutating").asBoolean()).isTrue();
        assertThat(transcripts.all().get(3).payload().get("args").get("source").asString()).isEqualTo("agent:t");
    }

    @Test
    void anAnswerWithoutFinishIsDoneWithTheTextAsItsSummary() {
        llm.thenText("Nothing here needs anyone.");

        run(DEFAULTS);

        assertThat(h.at(Agent.DONE)).singleElement().satisfies(d -> {
            assertThat(d.attribute("agent.outcome")).isEqualTo("done");
            assertThat(d.attribute("agent.summary")).isEqualTo("Nothing here needs anyone.");
        });
    }

    @Test
    void theIterationCapAndACutOffAnswerAreFailures() {
        llm.alwaysAsksFor(call("c", "event_get", Map.of()));
        run("""
                { "instructions": "i", "tools": ["event_get"], "maxIterations": 2 }""");
        assertThat(h.at(Agent.FAILURE)).singleElement().satisfies(f ->
                assertThat(f.attribute("failure.reason")).startsWith("iteration cap: 2"));
        assertThat(llm.sent()).hasSize(2);
        assertThat(transcripts.all().getLast().kind()).isEqualTo("note");

        StubLlmClient cutOff = new StubLlmClient().then(new LlmTurn(List.of(new LlmBlock.Text("The pump")), false,
                LlmTurn.StopReason.MAX_TOKENS, LlmTurn.Usage.NONE));
        Harness h2 = new Harness();
        h2.extra.add(new Agent(settings -> cutOff, List.of(), transcripts, properties, tenants,
                Clock.fixed(NOW, ZoneOffset.UTC)));
        h2.events(Map.of(), EVENTS);
        h2.pipe("agent", "{ \"instructions\": \"i\" }", Agent.DONE, Agent.ATTENTION, Agent.FAILURE);
        assertThat(h2.at(Agent.FAILURE)).singleElement().satisfies(f ->
                assertThat(f.attribute("failure.reason")).isEqualTo("output cut off at the output ceiling"));
        h2.close();
    }

    @Test
    void aSecondAttemptRunsOnlyIfTheFirstChangedNothing() {
        h.attempt = 2;
        transcripts.rows.clear();
        transcripts.seed(1, "tool_call", Map.of("name", "event_create", "mutating", true));
        llm.thenToolCalls(finish("done", "x"));

        run(DEFAULTS);

        assertThat(llm.sent()).as("the model is not asked again").isEmpty();
        assertThat(h.at(Agent.FAILURE)).singleElement().satisfies(f ->
                assertThat(f.attribute("failure.reason")).isEqualTo("not retried: the first attempt changed data"));
        assertThat(h.at(Agent.DONE)).isEmpty();

        // A first attempt that only read, or died before any tool call, is retried.
        Harness h2 = new Harness();
        h2.attempt = 2;
        MemoryTranscripts clean = new MemoryTranscripts();
        clean.seed(1, "tool_call", Map.of("name", "event_get", "mutating", false));
        StubLlmClient again = new StubLlmClient().thenToolCalls(finish("done", "fine"));
        h2.extra.add(new Agent(settings -> again, List.of(), clean, properties, tenants, Clock.fixed(NOW, ZoneOffset.UTC)));
        h2.events(Map.of(), EVENTS);
        h2.pipe("agent", "{ \"instructions\": \"i\" }", Agent.DONE, Agent.ATTENTION, Agent.FAILURE);
        assertThat(again.sent()).hasSize(1);
        assertThat(h2.at(Agent.DONE)).hasSize(1);
        h2.close();
    }

    @Test
    void cancellationStopsTheLoopAndFailsTheItems() {
        h.cancelled = () -> !llm.sent().isEmpty();
        h.extra.add(agent());
        h.events(Map.of(), EVENTS);
        llm.thenToolCalls(call("c1", "event_get", Map.of())).thenToolCalls(finish("done", "x"));

        RunResult result = h.run("""
                { "id": "p", "type": "agent", "properties": %s, "terminate": ["attention"] },
                { "id": "failed", "type": "test.collect" }""".formatted(DEFAULTS),
                """
                { "from": "in", "relationship": "success", "to": "p" },
                { "from": "p", "relationship": "failure", "to": "failed" }""", false, null, null);

        assertThat(result.status()).isEqualTo(RunResult.Status.CANCELLED);
        assertThat(api.calls).as("the tool call after the stop never ran").isEmpty();
        assertThat(llm.sent()).hasSize(1);
        assertThat(transcripts.all()).extracting(Row::kind).contains("note");
        assertThat(transcripts.all().getLast().payload().get("text").asString()).contains("stopped");
    }

    @Test
    void aLostLeaseEndsTheLoopAndFailsTheInvocation() {
        AtomicInteger tokens = new AtomicInteger();
        h.accessToken = () -> {
            if (tokens.incrementAndGet() > 1) {
                throw new IllegalStateException("Run no longer holds its lease; nothing more is sent to DataHub.");
            }
            return "harness-token";
        };
        h.extra.add(agent());
        h.events(Map.of(), EVENTS);
        llm.thenToolCalls(call("c1", "event_get", Map.of())).thenToolCalls(finish("done", "x"));

        RunResult result = h.run("""
                { "id": "p", "type": "agent", "properties": %s, "terminate": ["attention"] }""".formatted(DEFAULTS),
                "{ \"from\": \"in\", \"relationship\": \"success\", \"to\": \"p\" }", false, null, null);

        assertThat(result.errorCode()).isEqualTo(RunResult.PROCESSOR_FAILED);
        assertThat(result.error()).contains("lost its lease");
        assertThat(api.calls).isEmpty();
        assertThat(llm.sent()).hasSize(1);
    }

    @Test
    void aRateLimitedToolCallIsTriedOnceMoreAfterTheWaitAndThenGivenUp() {
        api.rateLimitNext = 1;
        llm.thenToolCalls(call("c1", "event_get", Map.of())).thenToolCalls(finish("done", "x"));
        run(DEFAULTS);
        assertThat(api.calls).hasSize(1);
        assertThat(api.refused).isEqualTo(1);
        assertThat(transcripts.all()).anySatisfy(r -> assertThat(r.kind().equals("note")
                && r.payload().get("text").asString().contains("Rate limited")).isTrue());
        LlmBlock.ToolResult ok = (LlmBlock.ToolResult) llm.sent().get(1).messages().get(2).blocks().getFirst();
        assertThat(ok.isError()).isFalse();

        api.rateLimitNext = 5;
        api.calls.clear();
        StubLlmClient again = new StubLlmClient().thenToolCalls(call("c2", "event_get", Map.of()))
                .thenToolCalls(finish("done", "y"));
        Harness h2 = new Harness();
        h2.extra.add(new Agent(settings -> again, List.of(new StatelessMcpClient("datahub-api", api.url(),
                HttpClient.newHttpClient(), JSON)), transcripts, properties, tenants, Clock.fixed(NOW, ZoneOffset.UTC)));
        h2.events(Map.of(), EVENTS);
        h2.pipe("agent", DEFAULTS, Agent.DONE, Agent.ATTENTION, Agent.FAILURE);
        LlmBlock.ToolResult failed = (LlmBlock.ToolResult) again.sent().get(1).messages().get(2).blocks().getFirst();
        assertThat(failed.isError()).isTrue();
        assertThat(failed.content()).contains("rate limiting");
        assertThat(api.calls).as("two tries, no more").isEmpty();
        assertThat(api.refused).isEqualTo(3);
        h2.close();
    }

    @Test
    void noModelConfiguredFailsBeforeAnyCall() {
        tenant.setLlm(null);
        llm.thenToolCalls(finish("done", "x"));

        run(DEFAULTS);

        assertThat(llm.sent()).isEmpty();
        assertThat(api.listCalls).isZero();
        assertThat(h.at(Agent.FAILURE)).singleElement().satisfies(f ->
                assertThat(f.attribute("failure.reason")).isEqualTo("no model configured"));
    }

    @Test
    void everyWaitingItemGoesThroughOneLoop() {
        h.extra.add(agent());
        h.events(Map.of("a", "1"), EVENTS.subList(0, 1));
        h.events(Map.of("a", "2"), EVENTS.subList(1, 2));
        h.events(Map.of("a", "3"), EVENTS);
        llm.thenToolCalls(finish("attention", "Three batches, one look."));

        h.pipe("agent", DEFAULTS, Agent.DONE, Agent.ATTENTION, Agent.FAILURE);

        assertThat(llm.sent()).hasSize(1);
        assertThat(llm.firstSent().messages().getFirst().text()).startsWith("4 event(s)");
        assertThat(h.at(Agent.DONE)).hasSize(3);
        assertThat(h.at(Agent.ATTENTION)).as("one attention item however many batches").hasSize(1);
    }

    @Test
    void toolsAreRoutedToTheServerThatAdvertisedThem() throws IOException {
        FakeMcp analysis = new FakeMcp("datahub-analysis");
        analysis.tool("analysis_related_series", true, false);
        servers.add(analysis);
        llm.thenToolCalls(call("c1", "event_get", Map.of()), call("c2", "analysis_related_series", Map.of()))
                .thenToolCalls(finish("done", "x"));

        run("""
                { "instructions": "i", "tools": ["event_get", "analysis_*"] }""");

        assertThat(api.calls).extracting(FakeMcp.Call::name).containsExactly("event_get");
        assertThat(analysis.calls).extracting(FakeMcp.Call::name).containsExactly("analysis_related_series");
        assertThat(records(Action.RECEIVE)).extracting(FlowRecord::transitUri)
                .containsExactly("mcp://datahub-api/event_get", "mcp://datahub-analysis/analysis_related_series");
    }

    @Test
    void theTranscriptIsCappedPerInvocation() {
        properties.setTranscriptMaxBytes(DataSize.ofBytes(600));
        llm.thenToolCalls(call("c1", "event_get", Map.of())).thenToolCalls(finish("done", "x"));

        run(DEFAULTS);

        assertThat(transcripts.all().getLast().kind()).isEqualTo("truncated");
        assertThat(transcripts.all()).filteredOn(r -> r.kind().equals("truncated")).hasSize(1);
        assertThat(transcripts.all().size()).isLessThan(8);
    }

    @Test
    void withNothingConnectedTheAgentRunsOnItsInstructionsAlone() {
        llm.thenToolCalls(call("c1", "event_get", Map.of("externalIds", List.of("pump_21:alarm"))))
                .thenToolCalls(finish("attention", "Pump 21 has been down since the morning."));
        h.extra.add(agent());

        RunResult result = h.flow("""
                { "id": "p", "type": "agent", "properties": %s },
                { "id": "done", "type": "test.collect" },
                { "id": "attention", "type": "test.collect" }""".formatted(DEFAULTS), """
                { "from": "p", "relationship": "done", "to": "done" },
                { "from": "p", "relationship": "attention", "to": "attention" }""", false, null, null);

        assertThat(result.error()).isNull();
        assertThat(llm.firstSent().systemPrompt()).doesNotContain("The events are from the window");
        assertThat(llm.firstSent().messages().getFirst().text()).startsWith("No events are attached")
                .contains("2026-10-01T");
        assertThat(h.at(Agent.DONE)).singleElement()
                .satisfies(done -> assertThat(done.attribute("agent.outcome")).isEqualTo("attention"));
        assertThat(h.at(Agent.ATTENTION)).singleElement().satisfies(a -> assertThat(a.rows().getFirst().metadata())
                .containsEntry("summary", "Pump 21 has been down since the morning."));
        assertThat(records(Action.RECEIVE)).extracting(FlowRecord::transitUri).containsExactly("mcp://datahub-api/event_get");
    }

    @Test
    void aQuietTickNeverReachesTheModel() throws IOException {
        try (FakeDatahub datahub = new FakeDatahub()) {
            h.extra.add(agent());
            RunResult result = h.flow("""
                    { "id": "src", "type": "read-events", "properties": { "type": ["error"] } },
                    { "id": "ai", "type": "agent", "properties": %s, "terminate": ["attention"] }""".formatted(DEFAULTS),
                    "{ \"from\": \"src\", \"relationship\": \"success\", \"to\": \"ai\" }", false, 7L, datahub.client());
            assertThat(result.status()).isEqualTo(RunResult.Status.SUCCEEDED);
            assertThat(llm.sent()).isEmpty();
            assertThat(api.listCalls).isZero();
        }
    }

    private List<FlowRecord> records(Action action) {
        return h.records.stream().filter(r -> r.processorId().equals("p") && r.action() == action).toList();
    }

    /** The transcript store, in a map: rows by attempt, whatever the run. */
    static final class MemoryTranscripts extends AgentTranscripts {
        final Map<Integer, List<Row>> rows = new LinkedHashMap<>();

        MemoryTranscripts() {
            super(null);
        }

        @Override
        public int append(UUID runId, int attempt, Instant time, String kind, Object payload) {
            List<Row> list = rows.computeIfAbsent(attempt, a -> new ArrayList<>());
            String text = JSON.writeValueAsString(payload);
            list.add(new Row(list.size(), time, kind, JSON.readTree(text)));
            return text.length() + 60;
        }

        @Override
        public List<Row> read(UUID runId, int attempt, long after) {
            List<Row> list = rows.getOrDefault(attempt, List.of());
            return list.subList((int) Math.min(list.size(), after + 1), list.size());
        }

        void seed(int attempt, String kind, Map<String, Object> payload) {
            append(null, attempt, Instant.EPOCH, kind, payload);
        }

        List<Row> all() {
            return rows.values().stream().flatMap(List::stream).toList();
        }
    }
}
