// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.engine.FlowRecord;
import ai.intellistream.datahub.flow.engine.FlowRecord.Action;
import ai.intellistream.datahub.flow.engine.RunResult;
import ai.intellistream.datahub.flow.processors.Harness.Captured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.intellistream.datahub.flow.processors.Harness.MINUTE;
import static ai.intellistream.datahub.flow.processors.Harness.T0;
import static ai.intellistream.datahub.flow.processors.Harness.WINDOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The source and the sinks against a fake datahub-api, through the real SDK client. */
class DatahubProcessorsTest {

    private final Harness h = new Harness();
    private final FakeDatahub api = new FakeDatahub();

    DatahubProcessorsTest() throws IOException {
    }

    @AfterEach
    void close() {
        api.close();
        h.close();
    }

    /** {@code src → out (collector)} with the source's properties. */
    private RunResult read(String properties) {
        return h.flow("""
                { "id": "src", "type": "read-timeseries", "properties": %s },
                { "id": "out", "type": "test.collect" }""".formatted(properties),
                "{ \"from\": \"src\", \"relationship\": \"success\", \"to\": \"out\" }",
                false, 7L, api.client());
    }

    private List<FlowRecord> records(String processor, Action action) {
        return h.records.stream().filter(r -> r.processorId().equals(processor) && r.action() == action)
                .toList();
    }

    // --- source -----------------------------------------------------------------------------------

    @Test
    void theSourceReadsTheWindowWithItsLookbackAndDedupesByTimestamp() {
        api.add("ts1", DatapointValueType.FLOAT)
                .put(T0 - 10 * MINUTE, 1)        // history, inside a 15-minute lookback
                .put(T0 - 20 * MINUTE, 99)       // before the lookback: not read
                .put(T0, 2)
                .put(T0 + MINUTE, 3)
                .put(T0 + MINUTE, 4)             // written twice, not merged: the last one wins
                .put(T0 + 60 * MINUTE, 5);       // the window's end is exclusive

        RunResult result = read("{ \"timeseries\": \"ts1\", \"lookback\": \"PT15M\" }");

        assertThat(result.error()).isNull();
        Captured item = h.at("out").getFirst();
        assertThat(item.t()).containsExactly(T0 - 10 * MINUTE, T0, T0 + MINUTE);
        assertThat(item.v()).containsExactly(1, 2, 4);
        assertThat(item.attributes()).containsEntry(Points.EXTERNAL_ID, "ts1")
                .containsEntry(Points.WINDOW_START, WINDOW.start().toString())
                .containsEntry(Points.WINDOW_END, WINDOW.end().toString());
        assertThat(records("src", Action.RECEIVE)).extracting(FlowRecord::transitUri)
                .containsExactly("datahub://timeseries/ts1?from=" + WINDOW.start().minusSeconds(900)
                        + "&to=" + WINDOW.end());
    }

    @Test
    void theSourceReadsEveryNumericTypeAsDoubles() {
        api.add("big", DatapointValueType.BIGINT).put(T0, 7);
        api.add("num", DatapointValueType.NUMERIC).put(T0, 1.25);
        api.add("dec", DatapointValueType.DECIMAL32).put(T0, -3.5);
        api.add("f32", DatapointValueType.FLOAT32).put(T0, 0.5);

        Map<String, Double> read = new LinkedHashMap<>();
        for (String series : List.of("big", "num", "dec", "f32")) {
            read("{ \"timeseries\": \"" + series + "\" }");
            read.put(h.at("out").getLast().attribute(Points.EXTERNAL_ID), h.at("out").getLast().v()[0]);
        }

        assertThat(read).containsExactly(Map.entry("big", 7.0), Map.entry("num", 1.25),
                Map.entry("dec", -3.5), Map.entry("f32", 0.5));
    }

    @Test
    void aSeriesWithNoPointsInTheWindowIsAnEmptyItem() {
        api.add("quiet", DatapointValueType.FLOAT).put(T0 - 90 * MINUTE, 1);

        read("{ \"timeseries\": \"quiet\" }");

        assertThat(h.at("out")).hasSize(1);
        assertThat(h.at("out").getFirst().t()).isEmpty();
    }

    @Test
    void aLongSeriesIsSplitIntoSeveralItems() {
        FakeDatahub.Series s = api.add("ts1", DatapointValueType.FLOAT);
        for (int i = 0; i < 5; i++) {
            s.put(T0 + i * MINUTE, i);
        }

        read("{ \"timeseries\": \"ts1\", \"maxPointsPerItem\": 2 }");

        assertThat(h.at("out")).extracting(c -> c.t().length).containsExactly(2, 2, 1);
        assertThat(h.at("out").get(2).v()).containsExactly(4);
    }

    @Test
    void aMissingSeriesFailsTheRunByDefault() {
        api.add("ts1", DatapointValueType.FLOAT).put(T0, 1);

        RunResult result = read("{ \"timeseries\": \"gone\" }");

        assertThat(result.errorCode()).isEqualTo(RunResult.PROCESSOR_FAILED);
        assertThat(result.error()).contains("gone");
        assertThat(h.at("out")).isEmpty();
    }

    @Test
    void aMissingSeriesCanBeSkipped() {
        api.add("ts1", DatapointValueType.FLOAT).put(T0, 1);

        RunResult result = read("{ \"timeseries\": \"gone\", \"onMissing\": \"skip\" }");

        assertThat(result.error()).isNull();
        assertThat(h.at("out")).isEmpty();
        assertThat(result.log()).anySatisfy(l -> assertThat(l.message()).contains("gone"));
    }

    @Test
    void aTextSeriesFailsClearly() {
        api.add("words", DatapointValueType.TEXT);

        RunResult result = read("{ \"timeseries\": \"words\" }");

        assertThat(result.errorCode()).isEqualTo(RunResult.PROCESSOR_FAILED);
        assertThat(result.error()).contains("words").contains("numeric series only");
    }

    // --- timeseries sink --------------------------------------------------------------------------

    /** {@code in → sink (target) [→ failure collector]} */
    private RunResult write(String properties, boolean preview, boolean collectFailure) {
        String processors = """
                { "id": "sink", "type": "write-timeseries", "properties": %s }"""
                .formatted(properties);
        String connections = "{ \"from\": \"in\", \"relationship\": \"success\", \"to\": \"sink\" }";
        if (collectFailure) {
            processors += ", { \"id\": \"failed\", \"type\": \"test.collect\" }";
            connections += ", { \"from\": \"sink\", \"relationship\": \"failure\", \"to\": \"failed\" }";
        }
        return h.run(processors, connections, preview, 7L, api.client());
    }

    @Test
    void theSinkWritesOnlyTheWindowsPoints() {
        api.add("ts1_f", DatapointValueType.FLOAT);
        h.series(new long[]{T0 - MINUTE, T0, T0 + MINUTE, T0 + 60 * MINUTE},
                new double[]{1, 2, 3, 4});

        RunResult result = write("{ \"target\": \"${timeseries.externalId}_f\" }", false, false);

        assertThat(result.error()).isNull();
        assertThat(api.written.get("ts1_f")).containsExactly(new double[]{T0, 2},
                new double[]{T0 + MINUTE, 3});
        assertThat(records("sink", Action.SEND)).extracting(FlowRecord::transitUri)
                .containsExactly("datahub://timeseries/ts1_f?from=" + WINDOW.start() + "&to="
                        + Instant.ofEpochMilli(T0 + MINUTE + 1));
    }

    @Test
    void theSinkWritesAtTheTargetsValueType() {
        api.add("ts1_f", DatapointValueType.FLOAT32);
        h.series(new long[]{T0}, new double[]{2.5});

        write("{ \"target\": \"ts1_f\" }", false, false);

        assertThat(api.written.get("ts1_f")).containsExactly(new double[]{T0, 2.5});
    }

    @Test
    void aPreviewWritesNothingAndSaysWhatItWouldHave() {
        api.add("ts1_f", DatapointValueType.FLOAT);
        h.series(new long[]{T0, T0 + MINUTE}, new double[]{1, 2});

        RunResult result = write("{ \"target\": \"ts1_f\" }", true, false);

        assertThat(result.error()).isNull();
        assertThat(api.requests).noneMatch(r -> r.contains("/timeseries/data/binary"));
        assertThat(api.written).isEmpty();
        assertThat(result.log()).anySatisfy(l -> assertThat(l.message())
                .isEqualTo("Would write 2 points to ts1_f"));
        assertThat(records("sink", Action.SEND)).isEmpty();
    }

    @Test
    void aMissingTargetFailsTheItemAndLeftUnconnectedTheRun() {
        h.series(new long[]{T0}, new double[]{1});

        RunResult result = write("{ \"target\": \"nowhere\" }", false, false);

        assertThat(result.errorCode()).isEqualTo(RunResult.ITEM_FAILED);
        assertThat(result.error()).contains("nowhere does not exist");
        assertThat(api.written).isEmpty();
    }

    @Test
    void aRefusedWriteGoesToFailureWhenThatIsConnected() {
        api.add("ts1_f", DatapointValueType.FLOAT);
        api.writeStatus = 403;
        h.series(new long[]{T0}, new double[]{1});

        RunResult result = write("{ \"target\": \"ts1_f\" }", false, true);

        assertThat(result.error()).isNull();
        assertThat(h.at("failed").getFirst().attribute("failure.reason"))
                .contains("refused").contains("403").contains("refused by the test");
    }

    @Test
    void aServerErrorIsThrownSoTheProcessorsRetryApplies() {
        api.add("ts1_f", DatapointValueType.FLOAT);
        api.writeStatus = 503;
        h.series(new long[]{T0}, new double[]{1});

        RunResult result = write("{ \"target\": \"ts1_f\" }", false, true);

        assertThat(result.errorCode()).isEqualTo(RunResult.PROCESSOR_FAILED);
        assertThat(h.at("failed")).isEmpty();
    }

    // --- events source ----------------------------------------------------------------------------

    /** {@code src → out (collector)} with the events source's properties. */
    private RunResult readEvents(String properties) {
        return h.flow("""
                { "id": "src", "type": "read-events", "properties": %s },
                { "id": "out", "type": "test.collect" }""".formatted(properties),
                "{ \"from\": \"src\", \"relationship\": \"success\", \"to\": \"out\" }",
                false, 7L, api.client());
    }

    @Test
    void theEventsSourceReadsTheWindowOfTheTypesAskedOldestFirst() {
        api.storeEvent("b", "alarm", T0 + 2 * MINUTE, "cleared");
        api.storeEvent("a", "alarm", T0, "active");
        api.storeEvent("c", "note", T0 + MINUTE, "");            // another type: not read
        api.storeEvent("d", "alarm", T0 + 59 * MINUTE, "active");
        api.storeEvent("e", "alarm", T0 + 60 * MINUTE, "active"); // the window's end is exclusive
        api.storeEvent("f", "alarm", T0 - MINUTE, "active");      // before the window

        RunResult result = readEvents("{ \"type\": [\"alarm\"] }");

        assertThat(result.error()).isNull();
        Captured item = h.at("out").getFirst();
        assertThat(item.rows()).extracting(EventRow::externalId).containsExactly("a", "b", "d");
        assertThat(item.rows().getFirst().status()).isEqualTo("active");
        assertThat(item.rows().getFirst().metadata()).containsEntry("k", "v");
        assertThat(item.attributes()).containsEntry(Points.WINDOW_START, WINDOW.start().toString());
        assertThat(records("src", Action.RECEIVE)).extracting(FlowRecord::transitUri)
                .containsExactly("datahub://events?type=alarm&from=" + WINDOW.start() + "&to=" + WINDOW.end());
    }

    @Test
    void excludedSourcesGoToTheApiAsAnExpressionItBinds() {
        api.storeEvent("a", "alarm", T0, "active");

        readEvents("{ \"excludeSource\": [\"agent:*\", \"sap\"] }");

        assertThat(api.filterRequests.getFirst().get("advancedFilter").stringValue())
                .isEqualTo("(source IS NULL OR source NOT LIKE 'agent:%') AND (source IS NULL OR source NOT LIKE 'sap')");
        assertThatThrownBy(() -> readEvents("{ \"excludeSource\": [\"it's\"] }"))
                .as("a quote never reaches the expression").hasMessageContaining("not a source pattern");
        assertThat(readEvents("{}").error()).isNull();
        assertThat(api.filterRequests.getLast().get("advancedFilter").isNull()).as("none asked, none sent").isTrue();
    }

    @Test
    void theEventsPagesFiltersGoToTheApiAndAnOrCannotUndoAnExclusion() {
        readEvents("""
                { "source": ["sap"], "dataSet": ["plant_a"], "metadata": ["severity=high", "ack"],
                  "advancedFilter": "status = 'open' OR status = 'new'", "excludeSource": ["agent:*"] }""");

        JsonNode sent = api.filterRequests.getFirst();
        assertThat(sent.get("advancedFilter").stringValue()).isEqualTo(
                "(status = 'open' OR status = 'new') AND (source IS NULL OR source NOT LIKE 'agent:%')");
        JsonNode filter = sent.get("filter");
        assertThat(filter.get("source").get(0).stringValue()).isEqualTo("sap");
        assertThat(filter.get("dataSetId").get(0).get("externalId").stringValue()).isEqualTo("plant_a");
        assertThat(filter.get("metadata").get("severity").stringValue()).isEqualTo("high");
        assertThat(filter.get("metadata").get("ack").isNull()).as("a key alone").isTrue();

        readEvents("{}");
        assertThat(api.filterRequests.getLast().get("filter").path("dataSetId").isMissingNode()
                || api.filterRequests.getLast().get("filter").get("dataSetId").isNull())
                .as("no datasets is no restriction, never an empty list").isTrue();
        assertThatThrownBy(() -> readEvents("{ \"metadata\": [\"=high\"] }")).hasMessageContaining("metadata");
    }

    @Test
    void theWindowCanBoundWhenAnEventWasRecordedInsteadOfWhenItHappened() {
        // Happened before the window, recorded in it: a late-stamped event, or a status change on
        // an older lifecycle.
        api.storeEvent("late", "alarm", T0 - 5 * MINUTE, T0 + MINUTE, "cleared");
        // Happened in the window, recorded after it: the next run's.
        api.storeEvent("early", "alarm", T0 + MINUTE, T0 + 61 * MINUTE, "active");
        // Recorded exactly at the window's end: the next window's.
        api.storeEvent("edge", "alarm", T0 + MINUTE, T0 + 60 * MINUTE, "active");

        RunResult result = readEvents("{ \"timeField\": \"createdTime\" }");

        assertThat(result.error()).isNull();
        assertThat(h.at("out").getFirst().rows()).extracting(EventRow::externalId).containsExactly("late");
        assertThat(h.at("out").getFirst().rows().getFirst().eventTime())
                .as("the row keeps the event's own time").isEqualTo(Instant.ofEpochMilli(T0 - 5 * MINUTE));
        assertThat(api.filterRequests.getFirst().get("filter").get("createdTime").get("min").isNull()).isFalse();
        assertThat(api.filterRequests.getFirst().get("filter").hasNonNull("eventTime")).isFalse();
    }

    @Test
    void manyEventsAreSplitIntoSeveralItems() {
        for (int i = 0; i < 5; i++) {
            api.storeEvent("e" + i, "alarm", T0 + i * MINUTE, "active");
        }
        readEvents("{ \"maxEventsPerItem\": 2 }");
        assertThat(h.at("out")).extracting(c -> c.rows().size()).containsExactly(2, 2, 1);
    }

    @Test
    void noEventsIsNoItem() {
        assertThat(readEvents("{}").error()).isNull();
        assertThat(h.at("out")).isEmpty();
    }

    @Test
    void eventsReadCanBeWrittenAgain() {
        api.storeEvent("a", "alarm", T0, "active");
        api.storeEvent("a", "alarm", T0 + MINUTE, "cleared");

        RunResult result = h.flow("""
                { "id": "src", "type": "read-events", "properties": { "type": ["alarm"] } },
                { "id": "ev", "type": "write-events", "properties": { "type": "alarm-copy" } }""",
                "{ \"from\": \"src\", \"relationship\": \"success\", \"to\": \"ev\" }",
                false, 7L, api.client());

        assertThat(result.error()).isNull();
        assertThat(api.events).extracting(e -> e.get("status").stringValue()).containsExactly("active", "cleared");
        assertThat(api.events).extracting(e -> e.get("type").stringValue()).containsOnly("alarm-copy");
    }

    // --- events sink ------------------------------------------------------------------------------

    private RunResult events(String properties, boolean preview) {
        return h.run("""
                { "id": "ev", "type": "write-events", "properties": %s }""".formatted(properties),
                "{ \"from\": \"in\", \"relationship\": \"success\", \"to\": \"ev\" }",
                preview, 7L, api.client());
    }

    private static final List<EventRow> ROWS = List.of(
            new EventRow("ts1:above:1", Instant.ofEpochMilli(T0), "active", Map.of("peak", "9.0")),
            new EventRow("ts1:above:1", Instant.ofEpochMilli(T0 + 3 * MINUTE), "cleared", Map.of()));

    @Test
    void theEventsSinkCreatesOneEventPerRowInTheFlowsDataset() {
        h.events(Map.of(Points.EXTERNAL_ID, "ts1"), ROWS);

        RunResult result = events("""
                { "type": "temperature", "subType": "over-limit", "series": "${timeseries.externalId}" }""",
                false);

        assertThat(result.error()).isNull();
        assertThat(api.events).hasSize(2);
        JsonNode active = api.events.getFirst();
        assertThat(active.get("externalId").stringValue()).isEqualTo("ts1:above:1");
        assertThat(active.get("status").stringValue()).isEqualTo("active");
        assertThat(active.get("type").stringValue()).isEqualTo("temperature");
        assertThat(active.get("subType").stringValue()).isEqualTo("over-limit");
        assertThat(active.get("dataSetId").asLong()).isEqualTo(7);
        assertThat(active.get("metadata").get("peak").stringValue()).isEqualTo("9.0");
        assertThat(active.get("metadata").get("series").stringValue()).isEqualTo("ts1");
        assertThat(api.events.get(1).get("status").stringValue()).isEqualTo("cleared");
        assertThat(records("ev", Action.SEND)).extracting(FlowRecord::transitUri)
                .containsExactly("datahub://events?type=temperature&subType=over-limit&from="
                        + Instant.ofEpochMilli(T0) + "&to=" + Instant.ofEpochMilli(T0 + 3 * MINUTE + 1));
    }

    @Test
    void theEventsSinkStampsTheSourceItIsGiven() {
        h.events(Map.of(Points.EXTERNAL_ID, "ts1"), ROWS);

        RunResult result = events("{ \"type\": \"temperature\", \"source\": \"agent:pump_watch\" }", false);

        assertThat(result.error()).isNull();
        assertThat(api.events).extracting(e -> e.get("source").stringValue()).containsOnly("agent:pump_watch");
    }

    @Test
    void theSameTransitionAlwaysGetsTheSameId() {
        h.events(Map.of(), ROWS);
        events("{ \"type\": \"temperature\" }", false);
        String first = api.events.getFirst().get("id").stringValue();

        Harness again = new Harness();
        again.events(Map.of(), ROWS);
        again.run("""
                { "id": "ev", "type": "write-events", "properties": { "type": "temperature" } }""",
                "{ \"from\": \"in\", \"relationship\": \"success\", \"to\": \"ev\" }", false, 7L,
                api.client());
        again.close();

        assertThat(api.events.get(2).get("id").stringValue()).isEqualTo(first);
        assertThat(api.events.get(1).get("id").stringValue()).isNotEqualTo(first);
        // Shaped as a UUID v7 on the event's time.
        assertThat(java.util.UUID.fromString(first).version()).isEqualTo(7);
        assertThat(java.util.UUID.fromString(first).getMostSignificantBits() >>> 16).isEqualTo(T0);
    }

    @Test
    void theEventsSinkCanOverrideTheExternalIdAndTheDataset() {
        api.datasets.put("alarms", 42L);
        h.events(Map.of(Points.EXTERNAL_ID, "ts1"), ROWS);

        events("""
                { "type": "temperature", "externalId": "${timeseries.externalId}:high", "dataSet": "alarms" }""",
                false);

        assertThat(api.events).extracting(e -> e.get("externalId").stringValue())
                .containsOnly("ts1:high");
        assertThat(api.events).extracting(e -> e.get("dataSetId").asLong()).containsOnly(42L);
    }

    @Test
    void anEventsPreviewCreatesNothing() {
        h.events(Map.of(), ROWS);

        RunResult result = events("{ \"type\": \"temperature\" }", true);

        assertThat(result.error()).isNull();
        assertThat(api.events).isEmpty();
        assertThat(result.log()).anySatisfy(l -> assertThat(l.message())
                .isEqualTo("Would create 2 events of type temperature"));
    }

    @Test
    void aDatasetThatIsGoneFailsTheItem() {
        h.events(Map.of(), ROWS);

        RunResult result = events("{ \"type\": \"temperature\", \"dataSet\": \"gone\" }", false);

        assertThat(result.errorCode()).isEqualTo(RunResult.ITEM_FAILED);
        assertThat(result.error()).contains("Dataset gone does not exist");
    }

    // --- together ---------------------------------------------------------------------------------

    @Test
    void thePlansExampleFlowRunsEndToEnd() {
        FakeDatahub.Series temp = api.add("ts_out_temp", DatapointValueType.FLOAT);
        for (int i = 0; i < 10; i++) {
            temp.put(T0 + i * MINUTE, i < 3 || i > 7 ? 20 : 70);   // clamped to 60, over the 50 below
        }
        api.add("ts_out_temp_f", DatapointValueType.FLOAT);

        RunResult result = h.flow("""
                { "id": "src", "type": "read-timeseries",
                  "properties": { "timeseries": "ts_out_temp" } },
                { "id": "f", "type": "clamp", "properties": { "min": 0, "max": 60 } },
                { "id": "out", "type": "write-timeseries",
                  "properties": { "target": "${timeseries.externalId}_f" } },
                { "id": "over", "type": "threshold",
                  "properties": { "threshold": 50, "minDuration": "PT2M" }, "terminate": ["success"] },
                { "id": "ev", "type": "write-events",
                  "properties": { "type": "temperature", "subType": "over-limit",
                                  "series": "${timeseries.externalId}" } }""", """
                { "from": "src", "relationship": "success", "to": "f" },
                { "from": "f", "relationship": "success", "to": "out" },
                { "from": "f", "relationship": "success", "to": "over" },
                { "from": "over", "relationship": "alarms", "to": "ev" }""", false, 7L, api.client());

        assertThat(result.error()).isNull();
        assertThat(api.written.get("ts_out_temp_f")).hasSize(10);
        assertThat(api.written.get("ts_out_temp_f").get(3)[1]).isEqualTo(60.0);
        assertThat(api.events).extracting(e -> e.get("status").stringValue())
                .containsExactly("active", "cleared");
        assertThat(api.events.getFirst().get("externalId").stringValue())
                .isEqualTo("ts_out_temp:above:" + Instant.ofEpochMilli(T0 + 3 * MINUTE));
    }
}
