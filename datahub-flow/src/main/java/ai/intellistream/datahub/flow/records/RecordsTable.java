// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Every statement against a tenant's {@code flow_run_event} table: the insert the records consumer
 * makes, and the two reads the REST interface makes.
 *
 * <p><b>The insert.</b> One INSERT per message, synchronous ({@code async_insert = 0}, overriding
 * the pooled client's default), with {@code insert_deduplication_token} set to
 * {@code <run>:<attempt>:<sequence>} — the batch's identity. The table keeps a deduplication window
 * ({@code non_replicated_deduplication_window}), so a batch inserted but not acknowledged when its
 * consumer died is not stored twice when Pulsar redelivers it. Inserting several messages as one
 * block would need a token for the set, and a redelivery can bring a different set, so each message
 * is its own insert: a run produces a handful of batches, not a stream, so the number of inserts is
 * small.
 *
 * <p>{@code ordinal} is a row's position in its batch: records of one invocation share their time,
 * so {@code (attempt, sequence, ordinal)} is what orders a run's records.
 */
public final class RecordsTable {

    private static final List<String> COLUMNS = List.of("event_time", "run_id", "attempt", "sequence",
            "ordinal", "flow_external_id", "flow_version", "processor", "processor_type", "action",
            "item_id", "parent_item_ids", "relationship", "content_kind", "size", "hash",
            "changed_attributes", "transit_uri", "duration_ms", "truncated");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RecordsTable() {
    }

    /** The deduplication token of a batch: its run, attempt and sequence. */
    static String token(FlowRecordBatch batch) {
        return batch.getRunId() + ":" + batch.getAttempt() + ":" + batch.getSequence();
    }

    /** Inserts one batch, deduplicated on its token; returns once ClickHouse has stored it. */
    static void insert(Client client, FlowRecordBatch batch, long timeoutSeconds) throws Exception {
        if (batch.getRecords().isEmpty()) {
            return;
        }
        InsertSettings settings = new InsertSettings()
                .setDeduplicationToken(token(batch))
                .serverSetting("async_insert", "0");
        try (var response = client.insert("flow_run_event", COLUMNS,
                new ByteArrayInputStream(rows(batch)), ClickHouseFormat.JSONEachRow, settings)
                .get(timeoutSeconds, TimeUnit.SECONDS)) {
            // Nothing to read from an insert.
        }
    }

    /** The batch as JSONEachRow, one line per record. Package-private for the test. */
    static byte[] rows(FlowRecordBatch batch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int ordinal = 0;
        for (FlowRecordBatch.Row r : batch.getRecords()) {
            ObjectNode row = JSON.createObjectNode();
            row.put("event_time", TIME.format(Instant.ofEpochMilli(r.getTimeMillis())));
            row.put("run_id", batch.getRunId());
            row.put("attempt", batch.getAttempt());
            row.put("sequence", batch.getSequence());
            row.put("ordinal", ordinal++);
            row.put("flow_external_id", text(batch.getFlowExternalId()));
            row.put("flow_version", batch.getFlowVersion());
            row.put("processor", text(r.getProcessor()));
            row.put("processor_type", text(r.getProcessorType()));
            row.put("action", text(r.getAction()));
            row.put("item_id", r.getItemId());
            ArrayNode parents = row.putArray("parent_item_ids");
            r.getParentItemIds().forEach(parents::add);
            row.put("relationship", text(r.getRelationship()));
            row.put("content_kind", text(r.getContentKind()));
            row.put("size", Math.max(r.getSize(), 0));
            // The content hash is an unsigned 64-bit value in a Java long.
            row.put("hash", new BigInteger(Long.toUnsignedString(r.getHash())));
            ObjectNode attributes = row.putObject("changed_attributes");
            r.getChangedAttributes().forEach((k, v) -> attributes.put(k, v == null ? "" : v));
            row.put("transit_uri", text(r.getTransitUri()));
            row.put("duration_ms", Math.min(Math.max(r.getDurationMillis(), 0), 0xFFFFFFFFL));
            row.put("truncated", r.isTruncated());
            out.writeBytes(JSON.writeValueAsBytes(row));
            out.write('\n');
        }
        return out.toByteArray();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------- reads

    /** One stored record, as the REST interface returns it. */
    public record StoredRecord(Instant time, int attempt, int sequence, int ordinal, String processor,
                               String processorType, String action, UUID itemId,
                               List<UUID> parentItemIds, String relationship, String contentKind,
                               long size, String hash, Map<String, String> changedAttributes,
                               String transitUri, long durationMillis, boolean truncated) {
    }

    /** Where a page of a run's records starts: after this attempt, batch and row. */
    public record Position(int attempt, int sequence, int ordinal) {
    }

    /** A run's records in order, after {@code after} (or from the start), at most {@code limit}. */
    static List<StoredRecord> list(Client client, UUID runId, Position after, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("run", runId.toString());
        String where = "run_id = {run:UUID}";
        if (after != null) {
            params.put("a", after.attempt());
            params.put("s", after.sequence());
            params.put("o", after.ordinal());
            where += " AND (attempt, sequence, ordinal) > ({a:UInt16}, {s:UInt32}, {o:UInt32})";
        }
        String sql = "SELECT event_time, attempt, sequence, ordinal, processor, processor_type, action,"
                + " item_id, parent_item_ids, relationship, content_kind, size, toString(hash) AS hash_text,"
                + " changed_attributes, transit_uri, duration_ms, truncated FROM flow_run_event WHERE "
                + where + " ORDER BY attempt, sequence, ordinal LIMIT " + limit;
        List<StoredRecord> out = new ArrayList<>();
        for (GenericRecord r : client.queryAll(sql, params)) {
            out.add(new StoredRecord(r.getInstant("event_time"), r.getInteger("attempt"),
                    (int) r.getLong("sequence"), (int) r.getLong("ordinal"), r.getString("processor"),
                    r.getString("processor_type"), r.getString("action"), r.getUUID("item_id"),
                    uuids(r.getList("parent_item_ids")), blankToNull(r.getString("relationship")),
                    blankToNull(r.getString("content_kind")), r.getLong("size"),
                    r.getString("hash_text"), stringMap(r.getObject("changed_attributes")),
                    blankToNull(r.getString("transit_uri")), r.getLong("duration_ms"),
                    r.getBoolean("truncated")));
        }
        return out;
    }

    /** A recorded write of a series: which run, attempt and processor claimed to write it, when. */
    public record Write(UUID runId, int attempt, String flowExternalId, int flowVersion, String processor,
                        Instant time, Instant windowStart, Instant windowEnd) {
    }

    /**
     * The recorded writes ({@code send}) of {@code externalId} whose window covers {@code at},
     * latest first. Answered from the table's lookup projection.
     */
    static List<Write> writes(Client client, String externalId, Instant at, int limit) {
        Map<String, Object> params = Map.of("x", externalId, "at", TIME.format(at));
        String sql = "SELECT run_id, attempt, flow_external_id, flow_version, processor, event_time,"
                + " transit_window_start, transit_window_end FROM flow_run_event"
                + " WHERE transit_entity = 'timeseries' AND transit_external_id = {x:String}"
                + " AND transit_window_start <= {at:DateTime64(3, 'UTC')}"
                + " AND transit_window_end > {at:DateTime64(3, 'UTC')} AND action = 'send'"
                + " ORDER BY event_time DESC LIMIT " + limit;
        List<Write> out = new ArrayList<>();
        for (GenericRecord r : client.queryAll(sql, params)) {
            out.add(new Write(r.getUUID("run_id"), r.getInteger("attempt"),
                    r.getString("flow_external_id"), (int) r.getLong("flow_version"),
                    r.getString("processor"), r.getInstant("event_time"),
                    r.getInstant("transit_window_start"), r.getInstant("transit_window_end")));
        }
        return out;
    }

    private static List<UUID> uuids(List<?> values) {
        List<UUID> out = new ArrayList<>();
        if (values != null) {
            for (Object v : values) {
                out.add(v instanceof UUID u ? u : UUID.fromString(v.toString()));
            }
        }
        return out;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new HashMap<>();
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : v.toString()));
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
