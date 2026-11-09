// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.EventsContent;
import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.CompiledFlow;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.FlowRecord;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import ai.intellistream.datahub.flow.engine.RunRequest;
import ai.intellistream.datahub.flow.engine.RunResult;
import ai.intellistream.datahub.sdk.client.DatahubClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the built-in processors for real, in the engine, between a source that emits given items
 * ({@code test.given}) and a collector that keeps what reaches it ({@code test.collect}), and checks
 * afterwards that every byte counted against a run's memory cap was given back.
 */
final class Harness implements AutoCloseable {

    static final TimeWindow WINDOW = new TimeWindow(Instant.parse("2026-10-01T10:00:00Z"),
            Instant.parse("2026-10-01T11:00:00Z"));
    static final long T0 = WINDOW.start().toEpochMilli();
    static final long MINUTE = 60_000;

    static List<Processor> builtIns() {
        return List.of(new TimeseriesSource(), new TimeseriesSink(), new EventsSource(), new TriggerEventsSource(),
                new EventsSink(), new Clamp(), new Threshold());
    }

    final RunExecutor executor = new RunExecutor(
            Clock.fixed(Instant.parse("2026-10-01T11:05:00Z"), ZoneOffset.UTC), d -> { });
    final Given given = new Given();
    final Collect collect = new Collect();
    final List<FlowRecord> records = new ArrayList<>();
    /** Processors beyond the built-ins a test runs, such as an agent made with fakes. */
    final List<Processor> extra = new ArrayList<>();
    /** What a run's {@code accessToken()} answers; a test replaces it to play a lost lease. */
    Supplier<String> accessToken = () -> "harness-token";
    /** Whether the run is told to stop; a test replaces it to cancel mid-way. */
    BooleanSupplier cancelled = () -> false;
    /** Which attempt of the run the next flow is, for a processor that looks at the one before. */
    int attempt = 1;
    private final List<RunResult> results = new ArrayList<>();

    /** A series {@code ts1} in {@link #WINDOW}, as the source would make it. */
    Harness series(long[] t, double[] v) {
        return series(Map.of(Points.EXTERNAL_ID, "ts1", Points.WINDOW_START, WINDOW.start().toString(),
                Points.WINDOW_END, WINDOW.end().toString()), t, v);
    }

    Harness series(Map<String, String> attributes, long[] t, double[] v) {
        given.inputs.add(new Input(attributes, t, v, null));
        return this;
    }

    Harness events(Map<String, String> attributes, List<EventRow> rows) {
        given.inputs.add(new Input(attributes, null, null, rows));
        return this;
    }

    /**
     * Runs {@code processors} (JSON array elements) wired by {@code connections}, after a
     * {@code test.given} processor with id {@code in}.
     */
    RunResult run(String processors, String connections) {
        return run(processors, connections, false, null, null);
    }

    RunResult run(String processors, String connections, boolean preview, Long dataSetId,
                  DatahubClient datahub) {
        return flow("{ \"id\": \"in\", \"type\": \"test.given\" }, " + processors, connections,
                preview, dataSetId, datahub);
    }

    /** Runs exactly {@code processors} wired by {@code connections}, as the flow's whole definition. */
    RunResult flow(String processors, String connections, boolean preview, Long dataSetId,
                   DatahubClient datahub) {
        return flow(processors, connections, null, preview, dataSetId, datahub);
    }

    /** As {@link #flow(String, String, boolean, Long, DatahubClient)}, with a trigger (JSON) when not null. */
    RunResult flow(String processors, String connections, String trigger, boolean preview, Long dataSetId,
                   DatahubClient datahub) {
        String json = """
                {
                  "schemaVersion": 1, "externalId": "t", "dataSet": "ds", "name": "Test",
                  "processors": [ %s ],
                  "connections": [ %s ]%s
                }
                """.formatted(processors, connections, trigger == null ? "" : ", \"trigger\": " + trigger);
        List<Processor> catalog = new ArrayList<>(builtIns());
        catalog.addAll(extra);
        catalog.add(given);
        catalog.add(collect);
        FlowValidator validator = new FlowValidator(catalog, FlowValidator.Limits.DEFAULTS);
        CompiledFlow flow = new FlowCompiler(validator).compile(FlowDefinitions.parse(json));
        RunResult result = executor.execute(flow, new RunRequest(UUID.randomUUID(), attempt, WINDOW, Map.of(),
                preview, 64L * 1024 * 1024, null, cancelled, dataSetId, datahub, accessToken), records::add);
        results.add(result);
        return result;
    }

    /**
     * {@code in → <type id="p" properties> → (each relationship → collector of that name)}.
     */
    RunResult pipe(String type, String properties, String... relationships) {
        StringBuilder processors = new StringBuilder("""
                { "id": "p", "type": "%s", "properties": %s }""".formatted(type, properties));
        StringBuilder connections = new StringBuilder("""
                { "from": "in", "relationship": "success", "to": "p" }""");
        for (String r : relationships) {
            processors.append(", { \"id\": \"").append(r).append("\", \"type\": \"test.collect\" }");
            connections.append(", { \"from\": \"p\", \"relationship\": \"").append(r)
                    .append("\", \"to\": \"").append(r).append("\" }");
        }
        RunResult result = run(processors.toString(), connections.toString());
        assertThat(result.error()).as("run error").isNull();
        return result;
    }

    /** What reached the collector called {@code id}. */
    List<Captured> at(String id) {
        return collect.captured.getOrDefault(id, List.of());
    }

    @Override
    public void close() {
        assertThat(results).flatExtracting(RunResult::log).extracting(RunResult.LogLine::message)
                .as("memory left counted after a run").noneMatch(m -> m.contains("not given back"));
    }

    record Input(Map<String, String> attributes, long[] t, double[] v, List<EventRow> rows) {
    }

    /** What an item held when it reached a collector. */
    record Captured(Map<String, String> attributes, long[] t, double[] v, List<EventRow> rows) {
        String attribute(String name) {
            return attributes.get(name);
        }
    }

    /** {@code test.given}: emits the harness's inputs on {@code success}. */
    static final class Given implements Processor {
        final List<Input> inputs = new ArrayList<>();

        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.given", "1.0.0")
                    .relationship(Relationship.of("success", "The given items")).build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            for (Input input : inputs) {
                Item item = session.putAllAttributes(session.create(), input.attributes());
                if (input.rows() != null) {
                    item = session.write(item, EventsContent.of(input.rows()));
                } else {
                    TimeseriesContent.Builder b = session.newTimeseries(input.t().length);
                    for (int i = 0; i < input.t().length; i++) {
                        b.add(input.t()[i], input.v()[i]);
                    }
                    item = session.write(item, b.build());
                }
                session.transfer(item, "success");
            }
        }
    }

    /** {@code test.collect}: keeps a copy of each item it receives, by its own id, and ends it. */
    static final class Collect implements Processor {
        final Map<String, List<Captured>> captured = new LinkedHashMap<>();

        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.collect", "1.0.0").build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Item item = session.get();
            Captured c;
            if (item.content() instanceof TimeseriesContent ts) {
                long[] t = new long[ts.rowCount()];
                double[] v = new double[ts.rowCount()];
                for (int i = 0; i < t.length; i++) {
                    t[i] = ts.timestamp(i);
                    v[i] = ts.value(i);
                }
                c = new Captured(item.attributes(), t, v, null);
            } else {
                c = new Captured(item.attributes(), null, null,
                        item.content(EventsContent.class).rows());
            }
            captured.computeIfAbsent(context.processorId(), k -> new ArrayList<>()).add(c);
            session.remove(item);
        }
    }

    /** {@code n} timestamps a minute apart from the window's start. */
    static long[] minutes(int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            t[i] = T0 + i * MINUTE;
        }
        return t;
    }
}
