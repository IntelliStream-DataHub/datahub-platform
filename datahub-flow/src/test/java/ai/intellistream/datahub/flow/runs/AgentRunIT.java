// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.config.AgentProperties;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import ai.intellistream.datahub.flow.fake.FakeMcp;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.processors.Agent;
import ai.intellistream.datahub.flow.processors.EventsSink;
import ai.intellistream.datahub.flow.processors.EventsSource;
import ai.intellistream.datahub.flow.records.FlowRecordBatch;
import ai.intellistream.datahub.flow.records.RunRecords;
import ai.intellistream.datahub.flow.store.AgentTranscripts;
import ai.intellistream.datahub.flow.store.AgentTranscripts.Row;
import ai.intellistream.datahub.flow.store.RunArchiver;
import ai.intellistream.datahub.flow.store.RunHistory;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.store.RunStore.FlowLimits;
import ai.intellistream.datahub.flow.store.RunStore.NewPreview;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.flow.store.SharedKvrocks;
import ai.intellistream.datahub.flow.store.TenantKvrocks;
import ai.intellistream.datahub.flow.tenant.FlowSchemaCheck;
import ai.intellistream.datahub.llm.LlmBackends;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import com.clickhouse.client.api.Client;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An agent, end to end, on the stores a deployment uses: the flow in Postgres, the run and its
 * transcript in KVRocks, the history in ClickHouse against the shipped schema, with datahub-api, the
 * MCP server and the model each a loopback fake. The run is claimed and executed by the coordinator
 * as a tick's run is, so what the engine does with the agent's outcomes, attempts and previews is
 * what is tested, not the processor alone (that is {@code AgentTest}).
 */
@Tag("integration")
class AgentRunIT {

    private static final String TENANT = "tenant-agent";
    private static final String FLOW = "pump_watch";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final TimeWindow WINDOW = new TimeWindow(Instant.parse("2026-10-12T01:00:00Z"),
            Instant.parse("2026-10-12T02:00:00Z"));
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private static ClickHouseContainer clickhouse;
    private static Client client;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private FlowRepository flows;
    private RunStore runs;
    private TenantKvrocks kvrocks;
    private AgentTranscripts transcripts;
    private RunHistory history;
    private RunArchiver archiver;
    private TenantConfigService tenants;
    private FlowSchemaCheck schemaCheck;
    private HttpServer datahub;
    private FakeMcp mcp;
    private FakeModel model;
    private final List<RunCoordinator> coordinators = new ArrayList<>();
    private final List<FlowRecordBatch> published = new CopyOnWriteArrayList<>();
    /** What datahub-api was asked for: every POST /events/filter body, and every event created. */
    private final List<JsonNode> filterRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> createdEvents = new CopyOnWriteArrayList<>();
    /** The events POST /events/filter answers from. */
    private final List<Map<String, Object>> stored = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        clickhouse = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.8.2.7"))
                .withUsername("tester").withPassword("test")
                .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));
        clickhouse.start();
        String endpoint = "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
        try (Client admin = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                .setUsername("tester").setPassword("test")).build()) {
            admin.query("CREATE DATABASE IF NOT EXISTS agent_run_it").get().close();
        }
        client = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                        .setUsername("tester").setPassword("test").setDefaultDatabase("agent_run_it"))
                .serverSetting("async_insert", "0").serverSetting("session_timezone", "UTC").build();
        String schema = Files.readAllLines(Path.of("../datahub-api/src/main/resources/db/clickhouse.sql"))
                .stream().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        for (String statement : schema.split(";")) {
            if (statement.contains("flow_run (") || statement.contains("flow_run_transcript (")) {
                client.query(statement).get().close();
            }
        }
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (clickhouse != null) {
            clickhouse.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                SharedPostgres.newDatabase("agent_run_it_" + DATABASES.incrementAndGet()),
                SharedPostgres.username(), SharedPostgres.password());
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        flows = new FlowRepository(jdbc);
        SharedKvrocks.flush();
        client.query("TRUNCATE TABLE flow_run").get().close();
        client.query("TRUNCATE TABLE flow_run_transcript").get().close();

        mcp = new FakeMcp("datahub-api");
        mcp.tool("event_get", true, false);
        mcp.tool("resource_get", true, false);
        mcp.tool("event_create", false, false);
        mcp.tool("event_delete", false, true);
        model = new FakeModel();

        // The tenant: the flow flag, a KVRocks, and a model of its own, as its Vault secret says.
        tenants = SharedKvrocks.tenants(TENANT);
        Tenant tenant = tenants.getConfig(TENANT);
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(true);
        tenant.setFeatures(features);
        TenantLlm llm = new TenantLlm();
        llm.setProvider(LlmProvider.OPENAI_COMPATIBLE);
        llm.setModel("fake");
        llm.setBaseUrl(model.url());
        tenant.setLlm(llm);
        tenants.cachedTenants = new LinkedHashMap<>(Map.of(TENANT, tenant));
        schemaCheck = mock(FlowSchemaCheck.class);
        when(schemaCheck.isProvisioned(anyString())).thenReturn(true);

        kvrocks = new TenantKvrocks(tenants);
        runs = new RunStore(kvrocks, Duration.ofSeconds(1));
        transcripts = new AgentTranscripts(kvrocks);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        when(pool.getClient(any())).thenReturn(client);
        when(pool.getReadOnlyClient(any())).thenReturn(client);
        history = new RunHistory(pool, tenants);
        archiver = new RunArchiver(tenants, runs, history, transcripts);
        TenantContext.setTenantId(TENANT);

        datahub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        datahub.createContext("/", this::datahub);
        datahub.start();
    }

    @AfterEach
    void tearDown() {
        coordinators.forEach(RunCoordinator::shutdown);
        datahub.stop(0);
        mcp.close();
        model.close();
        TenantContext.clear();
        kvrocks.close();
    }

    // ---------------------------------------------------------------- the tests

    @Test
    void anAgentReadsTheEventsCallsItsToolsAndRaisesAttentionWhichIsKeptWithTheRun() throws Exception {
        // One event that happened before the window but was recorded in it, one in it, and one the
        // agent wrote itself last time, which must not wake it again.
        storeEvent("pump_21:error", "error", "2026-10-12T00:45:00Z", "2026-10-12T01:10:00Z", null);
        storeEvent("pump_22:error", "error", "2026-10-12T01:30:00Z", "2026-10-12T01:31:00Z", null);
        storeEvent("agent:" + FLOW + ":earlier", "error", "2026-10-12T01:40:00Z", "2026-10-12T01:40:00Z",
                "agent:" + FLOW);
        model.thenToolCalls(call("c1", "event_get", Map.of("externalIds", List.of("pump_21:error"))))
                .thenToolCalls(call("c2", "resource_get", Map.of("externalId", "pump_21")))
                .thenToolCalls(call("c3", "event_create", Map.of("externalId", "pump_21:note", "type", "note")))
                .thenToolCalls(call("c4", "finish", Map.of("outcome", "attention",
                        "summary", "Pump 21 has failed twice this hour; the vendor is on site.")));
        long flow = deployedAgent(2);
        UUID run = pending(flow, 2);

        assertThat(coordinator().claim(TENANT, null)).containsExactly(run);
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));

        // What the source asked datahub-api for: the window on when events were recorded, its own
        // events left out.
        assertThat(filterRequests).singleElement().satisfies(request -> {
            assertThat(request.path("filter").path("createdTime").path("min").asString()).startsWith("2026-10-12T01:00");
            assertThat(request.path("filter").path("createdTime").path("max").asString()).startsWith("2026-10-12T02:00");
            assertThat(request.path("filter").path("type").get(0).asString()).isEqualTo("error");
            assertThat(request.path("advancedFilter").asString())
                    .isEqualTo("(source IS NULL OR source NOT LIKE 'agent:%')");
        });
        // What the model was given: the instructions, the two events, and only the allowed tools.
        assertThat(model.requests).hasSize(4);
        JsonNode first = model.requests.getFirst();
        assertThat(first.path("model").asString()).isEqualTo("fake");
        assertThat(first.path("messages").get(0).path("content").asString())
                .contains("look up the related resource").contains("2026-10-12T01:00:00Z to 2026-10-12T02:00:00Z");
        assertThat(first.path("messages").get(1).path("content").asString()).startsWith("2 event(s) in the window")
                .contains("pump_21:error").contains("pump_22:error").doesNotContain("earlier");
        assertThat(first.path("tools").valueStream().map(t -> t.path("function").path("name").asString()))
                .containsExactly("event_get", "resource_get", "event_create", "finish");
        // What reached the MCP server, as the run's identity, with the agent's provenance on its write.
        assertThat(mcp.calls).extracting(FakeMcp.Call::name).containsExactly("event_get", "resource_get", "event_create");
        assertThat(mcp.calls).allSatisfy(c -> assertThat(c.bearer()).isEqualTo("Bearer token-of-" + flow));
        assertThat(mcp.calls.get(2).args()).containsEntry("source", "agent:" + FLOW).containsEntry("type", "note");
        // The attention item became an event of the flow's dataset, written by the sink.
        assertThat(createdEvents).singleElement().satisfies(event -> {
            assertThat(event.path("externalId").asString()).isEqualTo("agent:" + FLOW + ":" + run);
            assertThat(event.path("type").asString()).isEqualTo("agent");
            assertThat(event.path("subType").asString()).isEqualTo("attention");
            assertThat(event.path("status").asString()).isEqualTo("open");
            assertThat(event.path("source").asString()).isEqualTo("agent:" + FLOW);
            assertThat(event.path("dataSetId").asString()).isEqualTo("7");
            assertThat(event.path("metadata").path("summary").asString()).startsWith("Pump 21 has failed twice");
            assertThat(event.path("metadata").path("runId").asString()).isEqualTo(run.toString());
            assertThat(event.path("metadata").path("flow").asString()).isEqualTo(FLOW);
        });
        // The records say what moved through which tool; the transcript says what was said.
        assertThat(mcpRecords()).containsExactly("receive mcp://datahub-api/event_get",
                "receive mcp://datahub-api/resource_get", "send mcp://datahub-api/event_create");
        List<Row> live = transcripts.read(run, 1, -1);
        assertThat(live).extracting(Row::kind).startsWith("system", "user")
                .containsSubsequence("tool_call", "tool_result", "tool_call", "tool_result", "tool_call", "tool_result");
        assertThat(live.stream().filter(r -> "tool_call".equals(r.kind()))
                .map(r -> r.payload().path("name").asString() + ":" + r.payload().path("mutating").asBoolean()))
                .containsExactly("event_get:false", "resource_get:false", "event_create:true", "finish:false");
        assertThat(live.stream().filter(r -> "usage".equals(r.kind())).mapToLong(r -> r.payload().path("inputTokens").asLong()).sum())
                .as("one usage row per model call, each with what the server reported").isEqualTo(4 * 100);
        assertThat(live).extracting(Row::seq).isSorted().doesNotHaveDuplicates();

        // Archived with the run: the same rows from ClickHouse once KVRocks has let the run go.
        archiver.archiveAll();
        assertThat(history.find(TENANT, run)).hasValueSatisfying(h -> assertThat(h.status()).isEqualTo("SUCCEEDED"));
        List<Row> archived = history.transcript(TENANT, run, 1, -1);
        assertThat(archived).extracting(Row::seq).containsExactlyElementsOf(live.stream().map(Row::seq).toList());
        assertThat(archived).extracting(Row::kind).containsExactlyElementsOf(live.stream().map(Row::kind).toList());
        assertThat(archived.getLast().payload()).isEqualTo(live.getLast().payload());
        Thread.sleep(1500);
        assertThat(runs.find(run)).as("gone from KVRocks after the finished TTL").isEmpty();
        assertThat(transcripts.read(run, 1, -1)).isEmpty();
        assertThat(history.transcript(TENANT, run, 1, 1)).extracting(Row::seq).startsWith(2L);
    }

    @Test
    void aWindowWithNoEventsNeverCallsTheModel() {
        long flow = deployedAgent(2);
        UUID run = pending(flow, 2);

        coordinator().claim(TENANT, null);
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));

        assertThat(filterRequests).hasSize(1);
        assertThat(model.requests).isEmpty();
        assertThat(mcp.listCalls).as("no tools listed for a run with nothing to do").isZero();
        assertThat(transcripts.read(run, 1, -1)).isEmpty();
        assertThat(createdEvents).isEmpty();
    }

    @Test
    void aModelServerDownOnTheFirstAttemptIsTriedAgainAndTheSecondAttemptRuns() {
        storeEvent("pump_21:error", "error", "2026-10-12T01:30:00Z", "2026-10-12T01:31:00Z", null);
        model.failNext = 1;
        model.thenToolCalls(call("c1", "finish", Map.of("outcome", "done", "summary", "Known and handled.")));
        long flow = deployedAgent(2);
        UUID run = pending(flow, 2);
        RunCoordinator a = coordinator();

        a.claim(TENANT, null);
        await(() -> runs.find(run).orElseThrow().attempt() == 2 && "PENDING".equals(runs.find(run).orElseThrow().status()));
        assertThat(runs.find(run).orElseThrow().errorCode()).isEqualTo("ITEM_FAILED");
        assertThat(transcripts.read(run, 1, -1)).extracting(Row::kind).containsExactly("system", "user", "note");
        assertThat(transcripts.read(run, 1, -1).getLast().payload().path("text").asString())
                .startsWith("The model call failed: Model server at").contains("HTTP 503");

        assertThat(a.claim(TENANT, null)).containsExactly(run);
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));

        assertThat(model.requests).as("the first attempt's request, and the second's").hasSize(2);
        assertThat(transcripts.read(run, 2, -1).stream().filter(r -> "tool_call".equals(r.kind())))
                .singleElement().satisfies(r -> assertThat(r.payload().path("name").asString()).isEqualTo("finish"));
        archiver.archiveAll();
        assertThat(history.transcript(TENANT, run, 1, -1)).extracting(Row::kind).containsExactly("system", "user", "note");
        assertThat(history.transcript(TENANT, run, 2, -1)).extracting(Row::kind).contains("tool_call", "usage");
    }

    @Test
    void anAttemptThatChangedDataBeforeFailingIsNotRunAgain() {
        storeEvent("pump_21:error", "error", "2026-10-12T01:30:00Z", "2026-10-12T01:31:00Z", null);
        model.thenToolCalls(call("c1", "event_create", Map.of("externalId", "pump_21:note", "type", "note")));
        model.failAfterNext = 1;
        long flow = deployedAgent(2);
        UUID run = pending(flow, 2);
        RunCoordinator a = coordinator();

        a.claim(TENANT, null);
        await(() -> runs.find(run).orElseThrow().attempt() == 2 && "PENDING".equals(runs.find(run).orElseThrow().status()));
        assertThat(mcp.calls).extracting(FakeMcp.Call::name).containsExactly("event_create");
        assertThat(a.claim(TENANT, null)).containsExactly(run);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));

        StoredRun failed = runs.find(run).orElseThrow();
        assertThat(failed.attempt()).isEqualTo(2);
        assertThat(failed.error()).contains("not retried: the first attempt changed data");
        assertThat(model.requests).as("the second attempt never asked the model").hasSize(2);
        assertThat(mcp.calls).as("and called no tool").hasSize(1);
        assertThat(transcripts.read(run, 2, -1)).extracting(Row::kind).containsExactly("note");
        // The failure is reported like any run's: the run-failed lifecycle in the flow's dataset.
        await(() -> !runs.find(run).orElseThrow().failureEventOwed());
        assertThat(createdEvents).singleElement().satisfies(event -> {
            assertThat(event.path("type").asString()).isEqualTo("flow");
            assertThat(event.path("subType").asString()).isEqualTo("run-failed");
            assertThat(event.path("metadata").path("errorCode").asString()).isEqualTo("ITEM_FAILED");
        });
    }

    @Test
    void aPreviewReadsAsTheCallerButExecutesNoToolThatMayWriteAndRaisesNoEvent() {
        storeEvent("pump_21:error", "error", "2026-10-12T01:30:00Z", "2026-10-12T01:31:00Z", null);
        model.thenToolCalls(call("c1", "event_get", Map.of("externalIds", List.of("pump_21:error"))))
                .thenToolCalls(call("c2", "event_create", Map.of("externalId", "pump_21:note", "type", "note")))
                .thenToolCalls(call("c3", "finish", Map.of("outcome", "attention", "summary", "Look at pump 21.")));
        long flow = deployedAgent(2);
        UUID run = UUID.randomUUID();
        runs.enqueuePreview(new NewPreview(run, flow, 1, FLOW, null, 7L, Duration.ofMinutes(15), WINDOW, null, "user-1"));

        assertThat(coordinator().claimPreview(TENANT, run, () -> "caller-token")).isTrue();
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));

        assertThat(mcp.calls).extracting(FakeMcp.Call::name).containsExactly("event_get");
        assertThat(mcp.calls.getFirst().bearer()).isEqualTo("Bearer caller-token");
        assertThat(createdEvents).as("the attention event is reported, not written").isEmpty();
        List<Row> rows = transcripts.read(run, 1, -1);
        assertThat(rows.stream().filter(r -> "tool_result".equals(r.kind())).map(r -> r.payload().path("text").asString()))
                .anySatisfy(text -> assertThat(text).contains("preview: not executed"));
        // Not forwarded, so not a change the next attempt would have to respect.
        assertThat(rows.stream().filter(r -> "tool_call".equals(r.kind())))
                .extracting(r -> r.payload().path("name").asString() + ":" + r.payload().path("mutating").asBoolean())
                .containsExactly("event_get:false", "event_create:false", "finish:false");
        // The model was told the call did not happen and finished anyway: attention, with nothing written.
        assertThat(model.requests).hasSize(3);
        assertThat(runs.find(run).orElseThrow().preview()).isTrue();
        assertThat(runs.find(run).orElseThrow().stats()).contains("Would create 1 events of type agent");
    }

    // ---------------------------------------------------------------- the fakes

    /** Enough of datahub-api for the source, the sink and the coordinator, on loopback. */
    private void datahub(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        Object answer = switch (path) {
            case "/datasets/byids" -> Map.of("items", List.of(Map.of("id", "7", "externalId", "ds")));
            case "/events/filter" -> {
                filterRequests.add(request);
                String advanced = request.hasNonNull("advancedFilter") ? request.get("advancedFilter").asString() : null;
                Instant min = Instant.parse(request.path("filter").path("createdTime").path("min").asString());
                Instant max = Instant.parse(request.path("filter").path("createdTime").path("max").asString());
                yield Map.of("items", stored.stream()
                        .filter(e -> {
                            Instant created = Instant.parse((String) e.get("createdTime"));
                            return !created.isBefore(min) && !created.isAfter(max);
                        })
                        // The one expression the source writes, applied as the api would.
                        .filter(e -> advanced == null || e.get("source") == null
                                || !((String) e.get("source")).startsWith("agent:"))
                        .toList());
            }
            case "/events/create" -> {
                request.path("items").forEach(createdEvents::add);
                yield request;
            }
            default -> Map.of("items", List.of());
        };
        byte[] body = JSON.writeValueAsBytes(answer);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void storeEvent(String externalId, String type, String eventTime, String createdTime, String source) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "e" + stored.size());
        event.put("externalId", externalId);
        event.put("type", type);
        event.put("status", "active");
        event.put("eventTime", eventTime);
        event.put("createdTime", createdTime);
        event.put("metadata", Map.of("severity", "high"));
        if (source != null) {
            event.put("source", source);
        }
        stored.add(event);
    }

    /** An OpenAI-compatible chat completions server on loopback, answering scripted turns. */
    private static final class FakeModel implements AutoCloseable {

        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        /** How many of the next requests answer 503, and how many requests to answer before failing. */
        int failNext;
        int failAfterNext = -1;
        private final Deque<Map<String, Object>> turns = new ArrayDeque<>();
        private final HttpServer server;

        FakeModel() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        @SafeVarargs
        final FakeModel thenToolCalls(Map<String, Object>... calls) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", "");
            message.put("tool_calls", List.of(calls));
            turns.add(turn(message, "tool_calls"));
            return this;
        }

        private static Map<String, Object> turn(Map<String, Object> message, String finishReason) {
            return Map.of("choices", List.of(Map.of("message", message, "finish_reason", finishReason)),
                    "usage", Map.of("prompt_tokens", 100, "completion_tokens", 10));
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            if (failNext > 0 || failAfterNext == 0) {
                failNext = Math.max(0, failNext - 1);
                failAfterNext = -1;
                byte[] body = "{\"error\":\"overloaded\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (failAfterNext > 0) {
                failAfterNext--;
            }
            Map<String, Object> next = turns.poll();
            if (next == null) {
                next = turn(Map.of("role", "assistant", "content", "Nothing more to do."), "stop");
            }
            byte[] body = JSON.writeValueAsBytes(next);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** A tool call as the wire has it. */
    private static Map<String, Object> call(String id, String name, Map<String, Object> args) {
        return Map.of("id", id, "type", "function",
                "function", Map.of("name", name, "arguments", JSON.writeValueAsString(args)));
    }

    // ---------------------------------------------------------------- fixtures

    /** The definition the console's Agents form writes, deployed to run as user-1. */
    private long deployedAgent(int maxAttempts) {
        String definition = """
                { "schemaVersion": 1, "kind": "agent", "externalId": "%s", "dataSet": "ds", "name": "Pump watch",
                  "processors": [
                    { "id": "events", "type": "read-events",
                      "properties": { "type": ["error"], "timeField": "createdTime", "excludeSource": ["agent:*"] } },
                    { "id": "agent", "type": "agent", "terminate": ["done"],
                      "properties": { "instructions": "For each event, look up the related resource and raise attention with a summary.",
                                      "tools": ["event_get", "resource_get", "event_create"] } },
                    { "id": "attention", "type": "write-events",
                      "properties": { "type": "agent", "subType": "attention", "source": "agent:%s" } }
                  ],
                  "connections": [
                    { "from": "events", "relationship": "success", "to": "agent" },
                    { "from": "agent", "relationship": "attention", "to": "attention" }
                  ] }
                """.formatted(FLOW, FLOW);
        long id = transaction.execute(s -> {
            long created = flows.create(FLOW, 7, "ds", "Pump watch", "agent", definition, "user-1").id();
            flows.deploy(created, 1, true, new Schedule(Schedule.MANUAL, null, null, null, null).toJson(JSON),
                    1, maxAttempts, Duration.ofMinutes(15), FlowRepository.Identity.deployer("user-1"),
                    "user-1", null, null);
            return created;
        });
        runs.syncFlow(new FlowLimits(id, FLOW, 7, true, true, 1));
        return id;
    }

    private UUID pending(long flow, int maxAttempts) {
        UUID id = UUID.randomUUID();
        runs.enqueue(new NewRun(id, flow, FLOW, 7, 1, maxAttempts, Duration.ofMinutes(15), WINDOW, null,
                "schedule", "user-1", "user-1", null));
        return id;
    }

    private RunCoordinator coordinator() {
        AgentProperties properties = new AgentProperties();
        Agent agent = new Agent(new LlmBackends(tenants, JSON),
                List.of(new StatelessMcpClient(mcp.name, mcp.url(), HttpClient.newHttpClient(), JSON)),
                transcripts, properties, tenants, Clock.systemUTC());
        List<Processor> processors = List.of(new EventsSource(), new EventsSink(), agent);
        FlowCompiler compiler = new FlowCompiler(new FlowValidator(processors, FlowValidator.Limits.DEFAULTS));
        RunCredentials credentials = (tenant, flowId) -> () -> "token-of-" + flowId;
        String api = "http://127.0.0.1:" + datahub.getAddress().getPort();
        RunExecution execution = new RunExecution(compiler, new RunExecutor(Clock.systemUTC(), d -> { }), flows,
                credentials, tenant -> Map.of(), api, REQUEST_TIMEOUT, JSON,
                (tenant, run, attempt, flow, version, dataSet) -> new RunRecords(run, attempt, flow, version,
                        dataSet, 100_000, Clock.systemUTC(), batch -> {
                            published.add(batch);
                            return CompletableFuture.completedFuture(null);
                        }), Duration.ofSeconds(5), 64L << 20);
        RunEvents events = new RunEvents(runs, credentials, api, REQUEST_TIMEOUT, 20);
        RunCoordinator c = new RunCoordinator(runs, flows, execution, events, tenants, schemaCheck, JSON,
                new RunCoordinator.Settings(LEASE, Duration.ofMillis(300), 2, 24, Duration.ZERO, Duration.ZERO,
                        Duration.ofSeconds(1), Duration.ZERO, Duration.ofMillis(100), Duration.ZERO),
                "a", System::nanoTime);
        coordinators.add(c);
        return c;
    }

    /** The records of tool calls, as "action uri", in order. */
    private List<String> mcpRecords() {
        return published.stream().flatMap(b -> b.getRecords().stream())
                .filter(r -> r.getTransitUri() != null && r.getTransitUri().startsWith("mcp://"))
                .map(r -> r.getAction() + " " + r.getTransitUri()).toList();
    }

    private static void await(BooleanSupplier condition) {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("Condition not met within 15 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
