// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the time-weighted-average weighting in
 * {@link ClickHouseDatapointService#buildAggregateQuery}: a bucket's {@code avgWeighted} average must
 * never fall outside {@code [min, max]}.
 *
 * <p>Reproduces the timezone-sensitive regression: the final row of a series has no next point, so its
 * duration weight came from an epoch guard. The old guard compared against an epoch <em>string
 * literal</em> parsed in the session/server timezone, so under a non-UTC timezone it missed and the
 * row got a large negative weight — pushing the bucket average above its max. Everything here runs
 * under a non-UTC {@code session_timezone} so the old query demonstrably breaks and the fixed one does
 * not. Needs a container runtime; run with {@code ./gradlew :datahub-infra:integrationTest}.
 */
@Tag("integration")
class ClickHouseAggregateWeightingIT {

    private static final String TZ = "America/New_York";

    // toString(...) so the Float64 results read back cleanly via GenericRecord#getString; the
    // weighting under test is unchanged by the wrapper.
    //
    // Two expressions, because the two tests run against two different subquery shapes and this was
    // one shared constant until the carry-in/ZOH rework moved the goalposts. buildAggregateQuery now
    // exposes contrib_value/contrib_weight/contrib_kind where it used to expose value and
    // duration_seconds; the sibling ITs (Zoh, Performance) were updated with it and this one was
    // missed, so it failed with UNKNOWN_IDENTIFIER against a perfectly correct production query.
    // Keeping them separate is the point: the legacy expression belongs only to the legacy query
    // below, and cannot be dragged along by the next change to the production one.

    /** Aggregates over what {@link ClickHouseDatapointService#buildAggregateQuery} exposes today. */
    private static final String AGGS =
            "toString(avgWeighted(contrib_value, contrib_weight)) AS avg, "
            + "toString(MIN(contrib_value)) AS min, toString(MAX(contrib_value)) AS max";

    /** Aggregates over the pre-fix query's columns, used only by the regression demonstration. */
    private static final String LEGACY_AGGS =
            "toString(avgWeighted(value, duration_seconds)) AS avg, "
            + "toString(MIN(value)) AS min, toString(MAX(value)) AS max";


    static Client client;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("weighting_it");
        // 'UTC' column so the stored instants stay near the epoch (10/20/30s) regardless of session tz.
        SharedClickHouse.execute(client, "CREATE TABLE dp (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Float64) "
                + "ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        // One 1-minute bucket, three points. The LAST point (00:00:30) is the series' final row — the
        // one whose duration weight the bug flipped negative — and it carries the extreme value 100.
        SharedClickHouse.execute(client, "INSERT INTO dp VALUES "
                + "(1, '1970-01-01 00:00:10.000', 1.0), "
                + "(1, '1970-01-01 00:00:20.000', 1.0), "
                + "(1, '1970-01-01 00:00:30.000', 100.0)");
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    private static List<GenericRecord> run(String query) {
        return client.queryAll(query + " SETTINGS session_timezone = '" + TZ + "'");
    }

    @Test
    void productionQuery_keepsAverageWithinMinMax() {
        String query = ClickHouseDatapointService.buildAggregateQuery(
                AGGS, "value", "1 minute", "dp", "WHERE timeseries_id = 1", false);
        List<GenericRecord> rows = run(query);
        assertFalse(rows.isEmpty(), "expected at least one bucket");
        for (GenericRecord r : rows) {
            double avg = Double.parseDouble(r.getString("avg"));
            double min = Double.parseDouble(r.getString("min"));
            double max = Double.parseDouble(r.getString("max"));
            assertTrue(avg >= min && avg <= max,
                    "avg " + avg + " must be within [" + min + ", " + max + "]");
        }
    }

    @Test
    void oldEpochLiteralGuard_producedAverageAboveMax() {
        // The pre-fix query, kept inline to prove the dataset is genuinely adversarial: under a non-UTC
        // session timezone the final row's weight went negative and pushed the average above the max.
        String buggy = "select time_bucket AS timestamp_g, " + LEGACY_AGGS + " from (SELECT value, "
                + "toStartOfInterval(timestamp, INTERVAL 1 minute, 'UTC') AS time_bucket, "
                + "leadInFrame(timestamp, 1) OVER (PARTITION BY timeseries_id ORDER BY timestamp ASC "
                + "ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS next_timestamp, "
                + "if(next_timestamp = toDateTime64('1970-01-01 00:00:00', 3), NULL, "
                + "toUnixTimestamp(next_timestamp) - toUnixTimestamp(timestamp)) AS duration_seconds "
                + "FROM dp WHERE timeseries_id = 1) WHERE isNotNull(next_timestamp) "
                + "GROUP BY timestamp_g ORDER BY timestamp_g";
        boolean anyAvgAboveMax = run(buggy).stream().anyMatch(r ->
                Double.parseDouble(r.getString("avg")) > Double.parseDouble(r.getString("max")));
        assertTrue(anyAvgAboveMax, "the old epoch-literal guard should have yielded avg > max here");
    }
}
