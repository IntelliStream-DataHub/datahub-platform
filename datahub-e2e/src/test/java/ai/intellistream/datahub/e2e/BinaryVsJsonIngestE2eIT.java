// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-to-end check that matters for the binary datapoint path: the same points, sent both
 * ways through the SDK, must end up identical in ClickHouse.
 *
 * <p>It goes through the whole platform, api to Pulsar to the consumer to ClickHouse, using only
 * what an SDK user can call. Storage is read directly for the assertions, because the read API
 * applies its own paging and would let a partial insert look complete.
 *
 * <p>Needs a running stack; see README.md. Skips when none is configured.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BinaryVsJsonIngestE2eIT {

    /** Long enough for a Pulsar hop plus a ClickHouse insert under load, short enough to fail fast. */
    private static final Duration SETTLE = Duration.ofMinutes(3);

    private static final int POINTS = 25_000;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private DatahubClient client;
    private String runId;
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    void setUp() {
        LiveStack.requireReachable();
        client = LiveStack.client();
        runId = "e2e_" + Long.toString(System.currentTimeMillis(), 36);
    }

    @AfterAll
    void tearDown() {
        if (client == null || created.isEmpty()) {
            return;
        }
        try {
            client.timeseries().delete(created.stream()
                    .map(ai.intellistream.datahub.models.IdCollection::createFromExternalId).toList());
        } catch (RuntimeException e) {
            System.err.println("cleanup failed for " + created + ": " + e.getMessage());
        }
    }

    @Test
    @DisplayName("The same points sent as JSON and as binary frames land identically")
    void bothPathsStoreTheSameRows() {
        String jsonSeries = runId + "_json";
        String binarySeries = runId + "_binary";
        long jsonId = createSeries(jsonSeries, "float32");
        long binaryId = createSeries(binarySeries, "float32");

        List<DatapointString> points = points(POINTS);

        IngestResult viaJson = client.timeseries().ingest(List.of(collection(jsonSeries, points)));
        assertThat(viaJson.isComplete()).as("JSON ingest: %s", viaJson).isTrue();
        assertThat(viaJson.succeeded()).isEqualTo(POINTS);

        IngestResult viaBinary = client.timeseries().ingestBinary(List.of(collection(binarySeries, points)));
        assertThat(viaBinary.isComplete()).as("binary ingest: %s", viaBinary).isTrue();
        assertThat(viaBinary.succeeded()).isEqualTo(POINTS);

        awaitRows("datapoints_float32", List.of(jsonId), POINTS);
        awaitRows("datapoints_float32", List.of(binaryId), POINTS);

        // The real assertion: not just the same count, the same bytes. A checksum over the whole
        // series catches a value mangled by one path's encoding that a count never would.
        String jsonDigest = digest("datapoints_float32", jsonId);
        String binaryDigest = digest("datapoints_float32", binaryId);
        assertThat(binaryDigest)
                .as("binary path stored different values than the JSON path for identical input")
                .isEqualTo(jsonDigest);
    }

    /**
     * Each value type, sent both ways. The assertion is that the two paths agree, not that storage
     * echoes the literal back: ClickHouse renders a number its own way (a float zero reads back as
     * {@code 0}, not {@code 0.0}), so comparing against the input would test the formatter rather
     * than the contract. What must hold is that a reader cannot tell which path wrote the row.
     */
    @Test
    @DisplayName("Every value type stores the same through both paths")
    void everyValueTypeMatchesTheJsonPath() {
        record Case(String valueType, String table, List<String> values) {
        }
        List<Case> cases = List.of(
                new Case("bigint", "datapoints_bigint", List.of("-9007199254740993", "0", "42")),
                new Case("float", "datapoints_float", List.of("-1.5", "0.0", "179.9514040223")),
                new Case("float32", "datapoints_float32", List.of("-1.5", "0.0", "3.25")),
                new Case("numeric", "datapoints_numeric", List.of("-1.234567", "0.000000", "999.999999")),
                new Case("decimal32", "datapoints_decimal32", List.of("-9999.9999", "0.0000", "1234.5678")),
                new Case("text", "datapoints_text", List.of("running", "FAULT", "stopped")),
                new Case("mixed", "datapoints_mixed", List.of("23.5", "FAULT", "24.5")));

        for (Case c : cases) {
            String jsonId = runId + "_json_" + c.valueType();
            String binaryId = runId + "_bin_" + c.valueType();
            long jsonKey = createSeries(jsonId, c.valueType());
            long binaryKey = createSeries(binaryId, c.valueType());

            List<DatapointString> values = new ArrayList<>();
            for (int i = 0; i < c.values().size(); i++) {
                values.add(new DatapointString(
                        Long.toString(START.plusSeconds(i).toEpochMilli()), c.values().get(i)));
            }

            IngestResult json = client.timeseries().ingest(List.of(collection(jsonId, values)));
            assertThat(json.isComplete()).as("%s via JSON: %s", c.valueType(), json).isTrue();
            IngestResult binary = client.timeseries().ingestBinary(List.of(collection(binaryId, values)));
            assertThat(binary.isComplete()).as("%s via binary: %s", c.valueType(), binary).isTrue();

            awaitRows(c.table(), List.of(jsonKey), c.values().size());
            awaitRows(c.table(), List.of(binaryKey), c.values().size());

            assertThat(storedValues(c.table(), binaryKey))
                    .as("value type %s differs between the binary and JSON paths", c.valueType())
                    .isEqualTo(storedValues(c.table(), jsonKey))
                    .hasSize(c.values().size());
        }
    }

    @Test
    @DisplayName("A binary request naming an unknown series is refused before anything is stored")
    void unknownSeriesIsRefused() {
        IngestResult result = client.timeseries().ingestBinary(
                Map.of(runId + "_does_not_exist",
                        List.of(ai.intellistream.datahub.sdk.timeseries.Datapoint.of(START, 1.0))));

        assertThat(result.isComplete()).isFalse();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().getFirst().statusCode()).isEqualTo(404);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private long createSeries(String externalId, String valueType) {
        Timeseries series = new Timeseries();
        series.setExternalId(externalId);
        series.setName(externalId);
        series.setValueType(valueType);
        series.setUnit("celsius");
        var response = client.timeseries().create(List.of(series));
        created.add(externalId);
        Long id = response.getItems().iterator().next().getId();
        assertThat(id).as("created series %s has no id", externalId).isNotNull();
        return id;
    }

    private static DatapointsCollection collection(String externalId, List<DatapointString> points) {
        DatapointsCollection collection = new DatapointsCollection();
        collection.setExternalId(externalId);
        collection.setDatapoints(points);
        return collection;
    }

    /** One point per second, values chosen to be exact in float32 so the digests can be compared. */
    private static List<DatapointString> points(int count) {
        List<DatapointString> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new DatapointString(
                    Long.toString(START.plusSeconds(i).toEpochMilli()),
                    Float.toString(i % 1024 + 0.25f)));
        }
        return points;
    }

    private void awaitRows(String table, List<Long> ids, long expected) {
        await(() -> LiveStack.clickHouseCount(table, ids) >= expected,
                () -> table + " reached " + LiveStack.clickHouseCount(table, ids) + " of " + expected + " rows");
    }

    private static void await(BooleanSupplier condition, java.util.function.Supplier<String> onTimeout) {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting", e);
            }
        }
        throw new AssertionError("timed out after " + SETTLE + ": " + onTimeout.get());
    }

    /**
     * A checksum of the whole series, ordered, computed inside ClickHouse. FINAL collapses the
     * ReplacingMergeTree duplicates a retry may have left, so this compares what a reader sees.
     */
    private static String digest(String table, long id) {
        return LiveStack.clickHouseQuery(
                "SELECT groupBitXor(cityHash64(timestamp, toString(value))) FROM "
                        + LiveStack.clickHouseDatabase() + "." + table + " FINAL WHERE timeseries_id = " + id).trim();
    }

    private static List<String> storedValues(String table, long id) {
        String column = table.endsWith("_mixed") ? "coalesce(toString(value_numeric), value_text)" : "toString(value)";
        String out = LiveStack.clickHouseQuery("SELECT " + column + " FROM " + LiveStack.clickHouseDatabase()
                + "." + table + " FINAL WHERE timeseries_id = " + id + " ORDER BY timestamp");
        return out.isBlank() ? List.of() : List.of(out.trim().split("\n"));
    }
}
