// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.ContentKind;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowRecord.Action;
import ai.intellistream.datahub.flow.engine.RunResult.Status;
import ai.intellistream.datahub.flow.fake.ExampleFlow;
import ai.intellistream.datahub.flow.fake.FakeProcessors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunExecutorTest {

    private static final long MB = 1024 * 1024;
    private static final TimeWindow WINDOW = new TimeWindow(Instant.parse("2026-10-01T10:00:00Z"),
            Instant.parse("2026-10-01T11:00:00Z"));

    private final List<Duration> slept = new ArrayList<>();
    private final RunExecutor executor = new RunExecutor(
            Clock.fixed(Instant.parse("2026-10-01T11:05:00Z"), ZoneOffset.UTC), slept::add);
    private final List<FlowRecord> records = new ArrayList<>();
    private final List<RunResult> results = new ArrayList<>();

    private final FakeProcessors.Sink sink = new FakeProcessors.Sink();
    private final FakeProcessors.EventsSink events = new FakeProcessors.EventsSink();

    @AfterEach
    void everyByteIsGivenBack() {
        assertThat(results).flatExtracting(RunResult::log).extracting(RunResult.LogLine::message)
                .as("memory left counted after a run").noneMatch(m -> m.contains("not given back"));
    }

    private CompiledFlow compile(String json, Processor... extra) {
        Map<String, Processor> catalog = new LinkedHashMap<>();
        for (Processor p : FakeProcessors.catalog()) {
            catalog.put(p.describe().name(), p);
        }
        for (Processor p : List.of(sink, events)) {
            catalog.put(p.describe().name(), p);
        }
        for (Processor p : extra) {
            catalog.put(p.describe().name(), p);
        }
        FlowValidator validator = new FlowValidator(catalog.values(), FlowValidator.Limits.DEFAULTS);
        return new FlowCompiler(validator).compile(FlowDefinitions.parse(json));
    }

    private RunResult run(CompiledFlow flow) {
        return run(flow, request(Map.of()));
    }

    private RunResult run(CompiledFlow flow, RunRequest request) {
        RunResult result = executor.execute(flow, request, records::add);
        results.add(result);
        return result;
    }

    private static RunRequest request(Map<String, Object> parameters) {
        return new RunRequest(UUID.randomUUID(), 1, WINDOW, parameters, false, 64 * MB, null, null, null, null);
    }

    /** A source reading one series, feeding {@code middle}, which feeds the sink. */
    private static String chain(String middleType, String middleExtra) {
        return """
                {
                  "schemaVersion": 1, "externalId": "chain", "dataSet": "ds", "name": "Chain",
                  "processors": [
                    { "id": "src", "type": "test.source", "properties": { "series": ["ts1"] } },
                    { "id": "mid", "type": "%s" %s },
                    { "id": "out", "type": "test.sink", "properties": { "target": "${timeseries.externalId}_out" } }
                  ],
                  "connections": [
                    { "from": "src", "relationship": "success", "to": "mid" },
                    { "from": "mid", "relationship": "success", "to": "out" }
                  ]
                }
                """.formatted(middleType, middleExtra);
    }

    private List<FlowRecord> records(String processorId, Action action) {
        return records.stream()
                .filter(r -> r.processorId().equals(processorId) && r.action() == action)
                .toList();
    }

    @Test
    void runsTheExampleFlow() {
        RunResult result = run(compile(ExampleFlow.DEFINITION));

        assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.errorCode()).isNull();
        // 1, 2, 3 × 1.8 + 32 = 33.8, 35.6, 37.4: written whole, and two points over the limit of 35.
        assertThat(sink.written).containsExactlyInAnyOrder("ts_out_temp_f:3", "ts_in_temp_f:3");
        assertThat(events.rows).containsExactly(2, 2);
        assertThat(result.parameters()).containsEntry("window", Duration.ofHours(1))
                .containsEntry("limit", new BigDecimal("35"));
        assertThat(result.processorVersions()).containsEntry("test.sink", "2.1.0")
                .containsEntry("test.source", "1.0.0");
        assertThat(result.records()).isEqualTo(records.size());
    }

    @Test
    void recordsWhatEveryProcessorDidThroughItsSession() {
        run(compile(ExampleFlow.DEFINITION));

        // A source's new item, filled with what it read, is one receive: no create or content change.
        assertThat(records("src", Action.CREATE)).isEmpty();
        assertThat(records("src", Action.CONTENT_MODIFIED)).isEmpty();
        assertThat(records("src", Action.RECEIVE)).hasSize(2).allSatisfy(r -> {
            assertThat(r.transitUri()).startsWith("datahub://timeseries/ts_");
            assertThat(r.changedAttributes()).containsKey("timeseries.externalId");
            assertThat(r.contentKind()).isEqualTo(ContentKind.TIMESERIES);
            assertThat(r.size()).isEqualTo(3);
            assertThat(r.runId()).isNotNull();
        });
        assertThat(records("f", Action.CONTENT_MODIFIED)).hasSize(2)
                .allSatisfy(r -> assertThat(r.contentKind()).isEqualTo(ContentKind.TIMESERIES));
        assertThat(records("out", Action.SEND)).extracting(FlowRecord::transitUri)
                .containsExactlyInAnyOrder("datahub://timeseries/ts_out_temp_f",
                        "datahub://timeseries/ts_in_temp_f");
        // over's success is terminated in the definition; out's success terminates by default.
        assertThat(records("over", Action.DROP)).hasSize(2)
                .allSatisfy(r -> assertThat(r.relationship()).isEqualTo("success"));
        // The sink's write ended each item: one record, carrying the relationship, no route or drop.
        assertThat(records("out", Action.SEND)).extracting(FlowRecord::relationship).containsOnly("success");
        assertThat(records("out", Action.DROP)).isEmpty();
        assertThat(records("out", Action.ROUTE)).isEmpty();
        assertThat(records("over", Action.ROUTE)).extracting(FlowRecord::relationship).doesNotContain("success");
        assertThat(records("over", Action.FORK)).hasSize(2).allSatisfy(r ->
                assertThat(r.parentItemIds()).hasSize(1));
        assertThat(records("over", Action.CONTENT_MODIFIED)).allSatisfy(r ->
                assertThat(r.contentKind()).isEqualTo(ContentKind.EVENTS));
    }

    @Test
    void aBranchGetsCopiesThatShareTheContent() {
        run(compile(ExampleFlow.DEFINITION));

        List<FlowRecord> routed = records("f", Action.ROUTE);
        List<FlowRecord> copies = records("f", Action.CLONE);
        assertThat(routed).hasSize(2);
        assertThat(copies).hasSize(4);
        for (FlowRecord original : routed) {
            List<FlowRecord> itsCopies = copies.stream()
                    .filter(c -> c.parentItemIds().equals(List.of(original.itemId()))).toList();
            assertThat(itsCopies).hasSize(2).allSatisfy(c -> {
                assertThat(c.itemId()).isNotEqualTo(original.itemId());
                assertThat(c.hash()).isEqualTo(original.hash());
                assertThat(c.relationship()).isEqualTo("success");
            });
        }
        // Each copy went on: one to the sink, one to the threshold.
        List<UUID> copyIds = copies.stream().map(FlowRecord::itemId).toList();
        assertThat(records("out", Action.SEND)).extracting(FlowRecord::itemId).allMatch(copyIds::contains);
    }

    @Test
    void theHashChangesWhereTheContentDoes() {
        run(compile(ExampleFlow.DEFINITION));

        long read = records("src", Action.RECEIVE).getFirst().hash();
        assertThat(read).isNotZero();
        assertThat(records("f", Action.CONTENT_MODIFIED)).extracting(FlowRecord::hash)
                .doesNotContain(read);
    }

    @Test
    void aFailedInvocationIsRolledBackAndRetried() {
        FakeProcessors.Flaky flaky = new FakeProcessors.Flaky(2);
        RunResult result = run(compile(chain("test.flaky",
                ", \"retry\": { \"maxAttempts\": 3, \"backoff\": \"PT2S\" }"), flaky));

        assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(flaky.calls).hasValue(3);
        assertThat(slept).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThat(result.log()).filteredOn(l -> "WARN".equals(l.level())).hasSize(2);
        // Only the attempt that succeeded left records; the failed ones left nothing behind.
        assertThat(records("mid", Action.ATTRIBUTES_MODIFIED)).hasSize(1);
        assertThat(records("mid", Action.CONTENT_MODIFIED)).hasSize(1);
        assertThat(sink.written).containsExactly("ts1_out:1");
    }

    @Test
    void aProcessorOutOfAttemptsFailsTheRun() {
        RunResult result = run(compile(chain("test.flaky", ", \"retry\": { \"maxAttempts\": 2 }"),
                new FakeProcessors.Flaky(10)));

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo(RunResult.PROCESSOR_FAILED);
        assertThat(result.failedProcessor()).isEqualTo("mid");
        assertThat(result.error()).contains("after 2 attempts").contains("Simulated failure 2");
        assertThat(sink.written).isEmpty();
    }

    @Test
    void goingOverTheMemoryCapFailsThatRunAlone() {
        String hog = """
                {
                  "schemaVersion": 1, "externalId": "hog", "dataSet": "ds", "name": "Hog",
                  "processors": [
                    { "id": "src", "type": "test.source", "properties": { "series": ["ts1"] } },
                    { "id": "hog", "type": "test.hog", "terminate": ["success"] }
                  ],
                  "connections": [ { "from": "src", "relationship": "success", "to": "hog" } ]
                }
                """;
        RunResult failed = run(compile(hog, new FakeProcessors.Hog()),
                new RunRequest(UUID.randomUUID(), 1, WINDOW, Map.of(), false, MB, null, null, null, null));

        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo(RunResult.MEMORY_LIMIT);
        assertThat(failed.failedProcessor()).isEqualTo("hog");

        // The executor carries on: the next run is unaffected.
        assertThat(run(compile(ExampleFlow.DEFINITION)).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void contentHeldOnTheHeapCountsAgainstTheCapToo() {
        // 100,000 points fit in 8 MB as a series (1.6 MB), but not as 100,000 event rows.
        String flow = """
                {
                  "schemaVersion": 1, "externalId": "alarms", "dataSet": "ds", "name": "Alarms",
                  "processors": [
                    { "id": "src", "type": "test.source",
                      "properties": { "series": ["ts1"], "points": 100000 } },
                    { "id": "over", "type": "test.threshold", "properties": { "threshold": 0 },
                      "terminate": ["success"] },
                    { "id": "ev", "type": "test.events.sink", "properties": { "type": "t" } }
                  ],
                  "connections": [
                    { "from": "src", "relationship": "success", "to": "over" },
                    { "from": "over", "relationship": "alarms", "to": "ev" }
                  ]
                }
                """;
        RunResult result = run(compile(flow),
                new RunRequest(UUID.randomUUID(), 1, WINDOW, Map.of(), false, 8 * MB, null, null, null, null));

        assertThat(result.errorCode()).isEqualTo(RunResult.MEMORY_LIMIT);
        assertThat(result.failedProcessor()).isEqualTo("over");
        assertThat(events.rows).isEmpty();
    }

    @Test
    void anItemSentToAnUnconnectedFailureFailsTheRun() {
        sink.failing.add("ts_out_temp_f");
        RunResult result = run(compile(ExampleFlow.DEFINITION));

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo(RunResult.ITEM_FAILED);
        assertThat(result.failedProcessor()).isEqualTo("out");
        assertThat(result.error()).contains("\"failure\"").contains("403 on ts_out_temp_f");
    }

    @Test
    void aProcessorThatTakesNothingFromAFullQueueFailsTheRun() {
        RunResult result = run(compile(chain("test.lazy", ""), new FakeProcessors.Lazy()));

        assertThat(result.errorCode()).isEqualTo(RunResult.NO_PROGRESS);
        assertThat(result.failedProcessor()).isEqualTo("mid");
    }

    @Test
    void anItemLeftUnaccountedForFailsTheInvocation() {
        RunResult result = run(compile(chain("test.forgetful", ""), new FakeProcessors.Forgetful()));

        assertThat(result.errorCode()).isEqualTo(RunResult.PROCESSOR_FAILED);
        assertThat(result.error()).contains("neither transferred nor removed");
    }

    @Test
    void parametersAreOverriddenTypedAndChecked() {
        CompiledFlow flow = compile(ExampleFlow.DEFINITION);

        RunResult lower = run(flow, request(Map.of("limit", new BigDecimal("33"))));
        assertThat(lower.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(events.rows).containsExactly(3, 3);

        RunResult wrongType = run(flow, request(Map.of("limit", "high")));
        assertThat(wrongType.errorCode()).isEqualTo(RunResult.INVALID_PARAMETERS);
        assertThat(wrongType.error()).contains("\"limit\"");

        RunResult unknown = run(flow, request(Map.of("speed", BigDecimal.ONE)));
        assertThat(unknown.errorCode()).isEqualTo(RunResult.INVALID_PARAMETERS);
    }

    @Test
    void aWindowReferenceNeedsARunWithAWindow() {
        RunResult result = run(compile(ExampleFlow.DEFINITION),
                new RunRequest(UUID.randomUUID(), 1, null, Map.of(), false, 64 * MB, null, null, null, null));

        assertThat(result.errorCode()).isEqualTo(RunResult.INVALID_PARAMETERS);
        assertThat(result.error()).contains("${run.window.start}");
    }

    @Test
    void secretsAreResolvedByNameAndOnlyTheirNamesKept() {
        FakeProcessors.Secretive secretive = new FakeProcessors.Secretive();
        CompiledFlow flow = compile(chain("test.secret",
                ", \"properties\": { \"password\": \"${secrets.db}\" }"), secretive);

        RunResult ok = run(flow, new RunRequest(UUID.randomUUID(), 1, WINDOW, Map.of(), false,
                64 * MB, Map.of("db", "s3cret")::get, null, null, null));
        assertThat(ok.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(secretive.seen).containsExactly("s3cret");
        assertThat(ok.secretsUsed()).containsExactly("db");
        assertThat(records).noneMatch(r -> r.changedAttributes().containsValue("s3cret"));

        RunResult missing = run(flow, request(Map.of()));
        assertThat(missing.errorCode()).isEqualTo(RunResult.MISSING_SECRET);
    }

    @Test
    void aStoppedRunEndsCancelled() {
        RunResult result = run(compile(ExampleFlow.DEFINITION),
                new RunRequest(UUID.randomUUID(), 1, WINDOW, Map.of(), false, 64 * MB, null, () -> true, null, null));

        assertThat(result.status()).isEqualTo(Status.CANCELLED);
        assertThat(result.errorCode()).isEqualTo(RunResult.CANCELLED);
        assertThat(sink.written).isEmpty();
    }

    @Test
    void aPreviewRunsEverythingButTheSinksWrite() {
        RunResult result = run(compile(ExampleFlow.DEFINITION),
                new RunRequest(UUID.randomUUID(), 1, WINDOW, Map.of(), true, 64 * MB, null, null, null, null));

        assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(sink.written).isEmpty();
        assertThat(records("out", Action.SEND)).hasSize(2);
    }
}
