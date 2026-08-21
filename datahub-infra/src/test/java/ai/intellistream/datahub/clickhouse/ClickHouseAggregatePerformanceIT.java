// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long-running load test: proves {@link ClickHouseDatapointService#buildAggregateQuery} is both
 * <b>correct</b> and <b>fast</b> at scale — ~200M rows across 100 timeseries, queried per series.
 *
 * <p><b>Irregular timestamps, exact answer.</b> Real datapoints do not arrive on a grid, so each hour of
 * every series holds 5 samples at the irregular offsets {@code {0, 137, 892, 1750, 3021}} seconds with
 * values {@code {5, 20, 8, 33, 12}} — hold durations {@code {137, 755, 858, 1271, 579}} s, none aligned to
 * a minute or hour. That makes the exact zero-order-hold (time-weighted) average of every hour
 * {@code Σ(value·hold)/3600 = 71540/3600 = 19.8722…}, whereas a plain unweighted mean is {@code 15.6}.
 * Because the per-hour pattern is identical and every hour/day contains whole periods, <em>every</em>
 * hourly and daily bucket must equal that value (and {@code min=5, max=33}, known sum) exactly, across
 * millions of buckets. Asserting that pins down proportional-to-duration weighting on irregular spacing —
 * the weak {@code min≤avg≤max} property would pass even with completely wrong weights. A separate check at
 * a granularity <em>unaligned</em> to the samples (7 min) asserts <b>weight conservation</b>: the clipped
 * sample + carry-in contributions tile each interior bucket to exactly its duration, with cross-boundary holds.
 *
 * <p>Data is generated server-side ({@code INSERT … SELECT FROM numbers(…)}) so the test buffers nothing,
 * and timed queries are wrapped in {@code count()/countIf()} so ClickHouse does the full work while
 * returning one row. Tagged {@code performance} (besides {@code integration}) so it runs on its own:
 * {@code ./gradlew :datahub-infra:performanceTest} (needs Docker/Podman). Time budgets are generous — they
 * fire only on a real regression; timings are logged to stdout.
 */
@Tag("integration")
@Tag("performance")
class ClickHouseAggregatePerformanceIT {

    private static final String TABLE = "datapoints_float";
    private static final int N_SERIES = 100;
    private static final long FIRST_ID = 1000L;
    private static final long BASE_MS = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final int HOUR_SEC = 3600;

    // Irregular intra-hour sample offsets (seconds) and their values; the pattern repeats each hour.
    // offsets[0]=0 so each hour is fully covered, and the last value is held to the hour's end.
    private static final int[] OFFSETS = {0, 137, 892, 1750, 3021};
    private static final int[] VALUES = {5, 20, 8, 33, 12};
    private static final int SLOTS_PER_HOUR = OFFSETS.length;               // 5
    private static final int HOURS_PER_SERIES = 24 * 16_667;               // 400,008 — whole days => complete daily buckets
    private static final long SAMPLES_PER_SERIES = (long) SLOTS_PER_HOUR * HOURS_PER_SERIES; // 2,000,040
    private static final long TOTAL_ROWS = (long) N_SERIES * SAMPLES_PER_SERIES;             // ~200.0M
    private static final long EXPECTED_HOURLY = HOURS_PER_SERIES;          // 400,008 complete hourly buckets/series
    private static final long EXPECTED_DAILY = HOURS_PER_SERIES / 24;      // 16,667 complete daily buckets/series
    private static final long EXPECTED_MINUTE = SAMPLES_PER_SERIES;        // every sample lands in its own minute

    // Exact per-hour expecteds, derived from OFFSETS/VALUES so they can never drift out of sync.
    private static final long WEIGHTED_SUM_PER_HOUR;
    private static final double MIN_V, MAX_V, SUM_HOURLY;
    static {
        long ws = 0, sum = 0;
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int k = 0; k < SLOTS_PER_HOUR; k++) {
            int end = (k + 1 < SLOTS_PER_HOUR) ? OFFSETS[k + 1] : HOUR_SEC; // last value held to hour end
            ws += (long) VALUES[k] * (end - OFFSETS[k]);
            sum += VALUES[k];
            mn = Math.min(mn, VALUES[k]);
            mx = Math.max(mx, VALUES[k]);
        }
        WEIGHTED_SUM_PER_HOUR = ws; // 71540
        SUM_HOURLY = sum;           // 78
        MIN_V = mn;                 // 5
        MAX_V = mx;                 // 33
    }
    // Injected as a SQL expression (not a Java double literal) so it matches ClickHouse's avgWeighted exactly.
    private static final String EXPECTED_AVG_SQL = "(" + WEIGHTED_SUM_PER_HOUR + ".0 / 3600.0)"; // 19.8722...
    private static final double SUM_DAILY = 24 * SUM_HOURLY;                // 1872

    private static final String AGG_FULL =
            "avgWeighted(contrib_value, contrib_weight) AS avg, min(contrib_value) AS min, "
          + "max(contrib_value) AS max, sumIf(contrib_value, contrib_kind = 'sample') AS sum";


    static Client client;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.clientBuilder("performance_it")
                .setSocketTimeout(5, ChronoUnit.MINUTES)
                .setExecutionTimeout(5, ChronoUnit.MINUTES)
                .build();
        SharedClickHouse.execute(client, "CREATE TABLE " + TABLE + " (timeseries_id Int64, timestamp DateTime64(3), value Float64) "
                + "ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");

        // Generate the irregular signal entirely server-side: sample i (=intDiv(number,N_SERIES)) of series
        // (number % N_SERIES) sits in hour intDiv(i,SLOTS) at intra-hour offset OFFSETS[i%SLOTS], value VALUES[..].
        long t0 = System.nanoTime();
        String i = "intDiv(number, " + N_SERIES + ")";
        String slot1 = "(" + i + " % " + SLOTS_PER_HOUR + " + 1)"; // ClickHouse arrays are 1-indexed
        String offsets = Arrays.toString(OFFSETS).replace(" ", "");
        String values = Arrays.toString(VALUES).replace(" ", "");
        String insert = "INSERT INTO " + TABLE + " SELECT "
                + FIRST_ID + " + (number % " + N_SERIES + ") AS timeseries_id, "
                + "fromUnixTimestamp64Milli(toInt64(" + BASE_MS + " + (intDiv(" + i + ", " + SLOTS_PER_HOUR + ") * "
                + HOUR_SEC + " + arrayElement(" + offsets + ", " + slot1 + ")) * 1000), 'UTC') AS timestamp, "
                + "toFloat64(arrayElement(" + values + ", " + slot1 + ")) AS value "
                + "FROM numbers(" + TOTAL_ROWS + ")";
        client.query(insert).get(5, TimeUnit.MINUTES);
        long insertMs = (System.nanoTime() - t0) / 1_000_000;

        long loaded = client.queryAll("SELECT count() AS c FROM " + TABLE).get(0).getLong("c");
        System.out.printf("[perf] generated %,d rows across %d series in %d ms%n", loaded, N_SERIES, insertMs);
        assertEquals(TOTAL_ROWS, loaded, "row count after server-side generation");
        assertTrue(insertMs < 300_000, "generation took " + insertMs + " ms");
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    private static String whereFor(long tsId) {
        return "WHERE timeseries_id = " + tsId
                + " AND timestamp >= '2000-01-01 00:00:00' AND timestamp < '2099-01-01 00:00:00'";
    }

    /**
     * Run the real aggregate query for one series at {@code granularity}, then (server-side) assert the
     * bucket count and that EVERY bucket exactly matches the known ZOH values. Returns elapsed millis.
     */
    private static long timedExactCheck(long tsId, String granularity, long expectedN, double expectedSum) {
        String inner = ClickHouseDatapointService.buildAggregateQuery(AGG_FULL, "value", granularity, TABLE, whereFor(tsId), false);
        String sql = "SELECT count() AS n, countIf("
                + "abs(avg - " + EXPECTED_AVG_SQL + ") > 1e-6 OR abs(min - " + MIN_V + ") > 1e-9 "
                + "OR abs(max - " + MAX_V + ") > 1e-9 OR abs(sum - " + expectedSum + ") > 1e-6) AS bad "
                + "FROM ( " + inner + " )";
        long t0 = System.nanoTime();
        List<GenericRecord> rows = client.queryAll(sql);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        GenericRecord r = rows.get(0);
        assertEquals(expectedN, r.getLong("n"), "bucket count for series " + tsId + " at " + granularity);
        assertEquals(0L, r.getLong("bad"),
                "series " + tsId + " has buckets whose ZOH avg/min/max/sum is not the exact expected value at " + granularity);
        return ms;
    }

    private static long medianQueryMs(String sql, int reps) {
        long[] t = new long[reps];
        for (int i = 0; i < reps; i++) {
            long s = System.nanoTime();
            client.queryAll(sql);
            t[i] = (System.nanoTime() - s) / 1_000_000;
        }
        Arrays.sort(t);
        return t[reps / 2];
    }

    /**
     * Every hourly bucket of every series must have the exact irregular-spacing ZOH average of 19.8722…
     * (not the plain-mean 15.6), across ~40M buckets — the core proportional-weighting accuracy check —
     * and the whole sweep must be quick.
     */
    @Test
    void hourlyExactZohAverageAcrossAllSeries() {
        long total = 0, max = 0;
        for (int s = 0; s < N_SERIES; s++) {
            long ms = timedExactCheck(FIRST_ID + s, "1 hour", EXPECTED_HOURLY, SUM_HOURLY);
            total += ms;
            max = Math.max(max, ms);
        }
        System.out.printf("[perf] hourly exact-check x %d series: total %d ms, max/series %d ms, %,d buckets/series%n",
                N_SERIES, total, max, EXPECTED_HOURLY);
        assertTrue(total < 180_000, "hourly sweep across all series took " + total + " ms");
    }

    /** Same exactness at the coarse (daily) end, across every series. */
    @Test
    void dailyExactZohAverageAcrossAllSeries() {
        long total = 0, max = 0;
        for (int s = 0; s < N_SERIES; s++) {
            long ms = timedExactCheck(FIRST_ID + s, "1 day", EXPECTED_DAILY, SUM_DAILY);
            total += ms;
            max = Math.max(max, ms);
        }
        System.out.printf("[perf] daily exact-check x %d series: total %d ms, max/series %d ms, %,d buckets/series%n",
                N_SERIES, total, max, EXPECTED_DAILY);
        assertTrue(total < 180_000, "daily sweep across all series took " + total + " ms");
    }

    /**
     * At a granularity UNALIGNED to the (already irregular) samples — 7 min — holds cross bucket boundaries,
     * exercising clipping + carry-in. Weight conservation: every interior bucket's contribution weights must
     * sum to exactly the bucket duration (420 s); only the leading/trailing partial bucket may differ.
     */
    @Test
    void weightConservationWithCrossBoundaryHolds() {
        String agg = "sum(contrib_weight) AS wsum";
        long maxMs = 0;
        for (int s = 0; s < 10; s++) {
            long tsId = FIRST_ID + s;
            String inner = ClickHouseDatapointService.buildAggregateQuery(agg, "value", "7 minute", TABLE, whereFor(tsId), false);
            String sql = "SELECT count() AS n, countIf(abs(wsum - 420) > 1e-6) AS bad FROM ( " + inner + " )";
            long t0 = System.nanoTime();
            List<GenericRecord> rows = client.queryAll(sql);
            maxMs = Math.max(maxMs, (System.nanoTime() - t0) / 1_000_000);
            GenericRecord r = rows.get(0);
            assertTrue(r.getLong("n") > 100, "expected many 7-min buckets for series " + tsId);
            assertTrue(r.getLong("bad") <= 2,
                    "series " + tsId + ": " + r.getLong("bad") + " interior 7-min buckets are not fully tiled to 420 s");
        }
        System.out.printf("[perf] weight-conservation (7-min, cross-boundary) x 10 series: max %d ms%n", maxMs);
        assertTrue(maxMs < 30_000, "weight-conservation query took " + maxMs + " ms");
    }

    /**
     * Finest granularity that still splits every sample into its own bucket (~2M buckets/series): the
     * heaviest {@code ARRAY JOIN}/grouping case. Assert completeness (bucket count) + speed.
     */
    @Test
    void minuteGranularityArrayJoinStress() {
        long maxMs = 0;
        for (int s = 0; s < 3; s++) {
            long tsId = FIRST_ID + s;
            String inner = ClickHouseDatapointService.buildAggregateQuery(AGG_FULL, "value", "1 minute", TABLE, whereFor(tsId), false);
            String sql = "SELECT count() AS n FROM ( " + inner + " )";
            long t0 = System.nanoTime();
            long n = client.queryAll(sql).get(0).getLong("n");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            maxMs = Math.max(maxMs, ms);
            assertEquals(EXPECTED_MINUTE, n, "minute bucket count for series " + tsId);
            System.out.printf("[perf] minute granularity series %d: %,d buckets in %d ms%n", tsId, n, ms);
        }
        assertTrue(maxMs < 30_000, "minute-granularity query took " + maxMs + " ms");
    }

    /**
     * One series must stay fast even though the table holds ~200M rows for 99 other series — proving the
     * {@code timeseries_id} ORDER BY-key prefix keeps it from scanning the whole table.
     */
    @Test
    void singleSeriesHourlyLatencyIsLowDespiteLargeTable() {
        String inner = ClickHouseDatapointService.buildAggregateQuery(AGG_FULL, "value", "1 hour", TABLE, whereFor(FIRST_ID), false);
        long ms = medianQueryMs("SELECT count() FROM ( " + inner + " )", 6);
        System.out.printf("[perf] single-series hourly median over a %,d-row table: %d ms%n", TOTAL_ROWS, ms);
        assertTrue(ms < 15_000, "single-series hourly query median " + ms + " ms");
    }

    /**
     * The ZOH machinery (two window functions + {@code ARRAY JOIN} + carry-in) should stay within a small
     * multiple of a plain {@code avg(value) GROUP BY bucket}. Loose bound — catches only an order-of-magnitude
     * regression (fixed per-query overhead dominates at these sizes).
     */
    @Test
    void zohOverheadVersusPlainAggregateIsBounded() {
        String inner = ClickHouseDatapointService.buildAggregateQuery(AGG_FULL, "value", "1 hour", TABLE, whereFor(FIRST_ID), false);
        String zohSql = "SELECT count() FROM ( " + inner + " )";
        String plainSql = "SELECT count() FROM (SELECT toStartOfInterval(timestamp, INTERVAL 1 hour, 'UTC') AS b, "
                + "avg(value) AS a FROM " + TABLE + " " + whereFor(FIRST_ID) + " GROUP BY b)";

        long zoh = medianQueryMs(zohSql, 6);
        long plain = medianQueryMs(plainSql, 6);
        System.out.printf("[perf] hourly ZOH %d ms vs plain aggregate %d ms (%.1fx)%n",
                zoh, plain, plain == 0 ? 0.0 : (double) zoh / plain);

        assertTrue(zoh < 15_000, "ZOH query median " + zoh + " ms");
        assertTrue(zoh <= Math.max(3_000, plain * 50L),
                "ZOH " + zoh + " ms is an outsized multiple of plain aggregate " + plain + " ms");
    }
}
