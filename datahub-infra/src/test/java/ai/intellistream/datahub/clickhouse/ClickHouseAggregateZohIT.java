// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the zero-order-hold (ZOH) time-weighted aggregate SQL from
 * {@link ClickHouseDatapointService#buildAggregateQuery} computes the exact per-bucket average against a
 * real ClickHouse server: each reading is held until the next, weights are clipped to bucket boundaries,
 * and the value held into a bucket from before its first sample is carried in. Needs a container runtime;
 * run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 *
 * <p>All expected values are hand-computed under the ZOH rules; note that every fully data-covered bucket
 * has contribution weights summing to the whole bucket duration (1800&nbsp;s for the 30-minute buckets).
 */
@Tag("integration")
class ClickHouseAggregateZohIT {

    static Client client;

    // Mirrors ClickHouseDatapointService.handleAggregates output for avg/min/max/sum, exercised together.
    private static final String AGGREGATES =
            "avgWeighted(contrib_value, contrib_weight) AS avg ,"
          + "min(contrib_value) as min ,"
          + "max(contrib_value) as max ,"
          + "sumIf(contrib_value, contrib_kind = 'sample') as sum ";

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("zoh_it");
        SharedClickHouse.execute(client, "CREATE TABLE datapoints_float (timeseries_id Int64, timestamp DateTime64(3), value Float64) "
                + "ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    /** Insert a whole series as one RowBinary batch: (timeseries_id, DateTime64(3) millis, little-endian Float64). */
    private static void insertSeries(long tsId, String[] isoTimes, double[] values) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        for (int i = 0; i < isoTimes.length; i++) {
            dos.writeLong(Long.reverseBytes(tsId));
            dos.writeLong(Long.reverseBytes(Instant.parse(isoTimes[i]).toEpochMilli()));
            dos.writeLong(Long.reverseBytes(Double.doubleToLongBits(values[i])));
        }
        dos.flush();
        try (InsertResponse ignored = client.insert("datapoints_float",
                new ByteArrayInputStream(bos.toByteArray()), ClickHouseFormat.RowBinary, new InsertSettings()).get()) {
            // rows inserted
        }
    }

    /**
     * Build and run the aggregate query for one series over a window wide enough to include every sample,
     * returning bucket-start (UTC) -&gt; [avg, min, max, sum]. Also asserts the universal ZOH invariant
     * {@code min <= avg <= max} on every returned bucket.
     */
    private static Map<Instant, double[]> runAgg(long tsId, String granularity) {
        // Literal filter (test-controlled ids/bounds); buildAggregateQuery splices it verbatim as the
        // innermost WHERE, exactly as the production parameterized filter is spliced.
        String where = "WHERE timeseries_id = " + tsId
                + " AND timestamp >= '2023-01-01 00:00:00' AND timestamp < '2025-06-01 00:00:00'";
        String sql = ClickHouseDatapointService.buildAggregateQuery(
                AGGREGATES, "value", granularity, "datapoints_float", where, false);

        Map<Instant, double[]> out = new LinkedHashMap<>();
        for (GenericRecord r : client.queryAll(sql)) {
            double avg = r.getDouble("avg");
            double min = r.getDouble("min");
            double max = r.getDouble("max");
            double sum = r.getDouble("sum");
            assertTrue(min - 1e-9 <= avg && avg <= max + 1e-9,
                    "avg " + avg + " outside [" + min + ", " + max + "]");
            out.put(r.getZonedDateTime("timestamp_g").toInstant(), new double[]{avg, min, max, sum});
        }
        return out;
    }

    private static void assertBucket(Map<Instant, double[]> res, String iso,
                                     double avg, double min, double max, double sum) {
        double[] v = res.get(Instant.parse(iso));
        assertNotNull(v, "no bucket at " + iso + " (got " + res.keySet() + ")");
        assertEquals(avg, v[0], 1e-6, "avg at " + iso);
        assertEquals(min, v[1], 1e-9, "min at " + iso);
        assertEquals(max, v[2], 1e-9, "max at " + iso);
        assertEquals(sum, v[3], 1e-6, "sum at " + iso);
    }

    /**
     * (a) A sample near a bucket's end held into the next bucket must NOT over-weight its own bucket, and
     * the value held into the next bucket must be carried in. 30-min buckets.
     */
    @Test
    void clipsCrossBoundaryHoldAndCarriesIn() throws Exception {
        insertSeries(101,
                new String[]{"2024-01-01T00:25:00Z", "2024-01-01T00:35:00Z",
                             "2024-01-01T00:55:00Z", "2024-01-01T01:05:00Z"},
                new double[]{10, 20, 30, 30});

        Map<Instant, double[]> res = runAgg(101, "30 minute");

        assertEquals(3, res.size(), "empty buckets must not be emitted");
        assertBucket(res, "2024-01-01T00:00:00Z", 10, 10, 10, 10);
        assertBucket(res, "2024-01-01T00:30:00Z", 20, 10, 30, 50);   // (10*300 + 20*1200 + 30*300)/1800
        assertBucket(res, "2024-01-01T01:00:00Z", 30, 30, 30, 30);
    }

    /**
     * (b) A value held across several fully-empty buckets is carried into the next bucket that has a
     * sample (regardless of distance); the empty buckets themselves are not emitted; the last sample is
     * held to its own bucket end.
     */
    @Test
    void carriesHeldValueAcrossMultiBucketGap() throws Exception {
        insertSeries(102,
                new String[]{"2024-01-01T00:10:00Z", "2024-01-01T02:10:00Z", "2024-01-01T02:20:00Z"},
                new double[]{100, 200, 200});

        Map<Instant, double[]> res = runAgg(102, "30 minute");

        assertEquals(2, res.size(), "the three empty buckets in the gap must not be emitted");
        assertBucket(res, "2024-01-01T00:00:00Z", 100, 100, 100, 100);
        // 02:00 bucket: 100 held 02:00-02:10 (carry-in), 200 for 02:10-02:20, 200 held 02:20-02:30.
        assertBucket(res, "2024-01-01T02:00:00Z", 500.0 / 3.0, 100, 200, 400);
    }

    /**
     * (c) When the carried-in held value lies OUTSIDE the range of the bucket's own samples, min/max must
     * still bracket the (correct) average — i.e. min/max include the carry-in, keeping avg in [min,max].
     */
    @Test
    void carryInOutsideSampleRangeKeepsAvgWithinMinMax() throws Exception {
        insertSeries(103,
                new String[]{"2024-01-01T00:20:00Z", "2024-01-01T00:40:00Z"},
                new double[]{0, 100});

        Map<Instant, double[]> res = runAgg(103, "30 minute");

        assertEquals(2, res.size());
        assertBucket(res, "2024-01-01T00:00:00Z", 0, 0, 0, 0);
        // 00:30 bucket: 0 held 00:30-00:40 (carry-in), 100 held 00:40-01:00. avg=200/3, min=0 from carry-in.
        assertBucket(res, "2024-01-01T00:30:00Z", 200.0 / 3.0, 0, 100, 100);
    }

    /**
     * (e) Calendar-unit buckets (1 day) across the 2024 leap day, verifying {@code bucket_end =
     * toStartOfInterval(..) + INTERVAL 1 day} lands on Feb 29 (not Mar 1) and the bucket starts are exact.
     */
    @Test
    void calendarDayBucketsAcrossLeapDay() throws Exception {
        insertSeries(105,
                new String[]{"2024-02-28T12:00:00Z", "2024-02-29T06:00:00Z", "2024-02-29T18:00:00Z"},
                new double[]{10, 40, 40});

        Map<Instant, double[]> res = runAgg(105, "1 day");

        assertEquals(2, res.size());
        assertBucket(res, "2024-02-28T00:00:00Z", 10, 10, 10, 10);
        // Feb 29 fully covered: 10 for 6h (carry-in) + 40 for 12h + 40 for 6h -> (10*6 + 40*12 + 40*6)/24.
        assertBucket(res, "2024-02-29T00:00:00Z", 32.5, 10, 40, 80);
    }

    /**
     * The ARRAY JOIN-in-one-block shape must execute without tripping ClickHouse's plan-optimizer limit
     * (error 572, TOO_MANY_QUERY_PLAN_OPTIMIZATIONS) that the previous outer-WHERE-on-window shape risked.
     */
    @Test
    void aggregateQueryDoesNotHitPlanOptimizerLimit() throws Exception {
        insertSeries(106,
                new String[]{"2024-03-01T00:05:00Z", "2024-03-01T00:35:00Z"},
                new double[]{7, 9});
        Map<Instant, double[]> res = assertDoesNotThrow(() -> runAgg(106, "30 minute"));
        assertEquals(2, res.size());
    }
}
