// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.intellistream.datahub.testsupport.SharedClickHouse.execute;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code flow_run_event} as the shipped schema file declares it: the whole of clickhouse.sql applies
 * to an empty database, the transit columns are derived from the URI when a row is written, a batch
 * re-inserted under the same deduplication token is stored once, and the lookup by series and time
 * is answered from the projection.
 */
@Tag("integration")
class FlowRunEventTableIT {

    private static final String RUN = "0192a6b0-0000-7000-8000-000000000001";

    private static Client client;

    @BeforeAll
    static void applySchema() throws Exception {
        client = SharedClickHouse.newClient("flow_run_event_it");
        // The file the dev stack's init script applies to each tenant database. Statements end
        // with a semicolon; comment lines may contain one, so they are dropped first.
        String schema = Files.readAllLines(Path.of("../datahub-api/src/main/resources/db/clickhouse.sql"))
                .stream().filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String statement : schema.split(";")) {
            if (!statement.isBlank()) {
                execute(client, statement);
            }
        }
    }

    @Test
    void theTransitColumnsAreDerivedFromTheUri() throws Exception {
        execute(client, "TRUNCATE TABLE flow_run_event");
        insert("t1", List.of(
                row("read", "datahub://timeseries/ts_raw%3Atemp?from=2026-10-12T10%3A00%3A00Z&to=2026-10-12T11%3A00%3A00Z"),
                row("send", "datahub://events/flow%3Atemp_hourly%3A1"),
                row("send", "https://api.example.com/v1/readings"),
                row("route", "")));

        List<List<String>> rows = select("SELECT action, transit_scheme, transit_entity, transit_external_id,"
                + " toString(transit_window_start), toString(transit_window_end)"
                + " FROM flow_run_event ORDER BY action, transit_scheme");

        assertThat(rows).containsExactlyInAnyOrder(
                List.of("read", "datahub", "timeseries", "ts_raw:temp",
                        "2026-10-12 10:00:00.000", "2026-10-12 11:00:00.000"),
                List.of("send", "datahub", "events", "flow:temp_hourly:1",
                        "1970-01-01 00:00:00.000", "1970-01-01 00:00:00.000"),
                List.of("send", "https", "", "", "1970-01-01 00:00:00.000", "1970-01-01 00:00:00.000"),
                List.of("route", "", "", "", "1970-01-01 00:00:00.000", "1970-01-01 00:00:00.000"));
    }

    @Test
    void aBatchInsertedTwiceUnderOneTokenIsStoredOnce() throws Exception {
        execute(client, "TRUNCATE TABLE flow_run_event");
        List<String> batch = List.of(row("read", "datahub://timeseries/a"), row("send", "datahub://timeseries/b"));

        insert(RUN + ":1:0", batch);
        insert(RUN + ":1:0", batch);   // the consumer died before acknowledging; Pulsar redelivers
        assertThat(count()).isEqualTo(2);

        insert(RUN + ":1:1", batch);   // the next batch, identical content, is a different batch
        assertThat(count()).isEqualTo(4);
    }

    @Test
    void theLookupBySeriesAndTimeUsesTheProjection() throws Exception {
        execute(client, "TRUNCATE TABLE flow_run_event");
        // Enough rows over enough series that reading by series is cheaper through the projection
        // than through the table's own (run_id, event_time) order; on a handful of rows the
        // optimizer rightly reads the table.
        execute(client, "INSERT INTO flow_run_event (event_time, run_id, attempt, sequence,"
                + " flow_external_id, flow_version, processor, processor_type, action, item_id,"
                + " parent_item_ids, relationship, content_kind, size, hash, changed_attributes,"
                + " transit_uri, duration_ms) SELECT now64(3), generateUUIDv4(number), 1, 0, 'f', 1,"
                + " 'out', 'timeseries.write', 'send', generateUUIDv4(number), [], 'success',"
                + " 'timeseries', 1, 1, map(), concat('datahub://timeseries/s', toString(number % 5000),"
                + " '?from=2026-10-12T09:00:00Z&to=2026-10-12T10:00:00Z'), 1 FROM numbers(200000)");
        insert("t3", List.of(row("send",
                "datahub://timeseries/ts_out?from=2026-10-12T10:00:00Z&to=2026-10-12T11:00:00Z")));

        // force_optimize_projection fails the query unless a projection serves it.
        List<List<String>> rows = select("SELECT toString(run_id) FROM flow_run_event"
                + " WHERE transit_entity = 'timeseries' AND transit_external_id = 'ts_out'"
                + " AND transit_window_start <= '2026-10-12 10:30:00' AND transit_window_end > '2026-10-12 10:30:00'"
                + " ORDER BY event_time DESC LIMIT 1 SETTINGS force_optimize_projection = 1");

        assertThat(rows).containsExactly(List.of(RUN));
    }

    private static String row(String action, String transitUri) {
        return "(now64(3), '" + RUN + "', 1, 0, 'temp_hourly', 1, 'src', 'timeseries.read', '"
                + action + "', generateUUIDv4(), [], 'success', 'timeseries', 100, 42, map(), '"
                + transitUri + "', 3, false)";
    }

    /** One INSERT is one block, synchronous so the token is honoured whatever the client defaults. */
    private static void insert(String token, List<String> rows) {
        execute(client, "INSERT INTO flow_run_event (event_time, run_id, attempt, sequence,"
                + " flow_external_id, flow_version, processor, processor_type, action, item_id,"
                + " parent_item_ids, relationship, content_kind, size, hash, changed_attributes,"
                + " transit_uri, duration_ms, truncated)"
                + " SETTINGS insert_deduplication_token = '" + token + "', async_insert = 0"
                + " VALUES " + String.join(", ", rows));
    }

    private static long count() throws Exception {
        return Long.parseLong(select("SELECT count() FROM flow_run_event").getFirst().getFirst());
    }

    private static List<List<String>> select(String sql) throws Exception {
        List<List<String>> out = new ArrayList<>();
        for (GenericRecord r : client.queryAll(sql)) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= r.getSchema().getColumns().size(); i++) {
                values.add(r.getString(i));
            }
            out.add(values);
        }
        return out;
    }
}
