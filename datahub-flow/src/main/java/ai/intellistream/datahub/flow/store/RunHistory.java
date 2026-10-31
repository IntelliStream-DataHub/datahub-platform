// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.tenant.TenantConfigService;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The history of finished runs, in the tenant's ClickHouse ({@code flow_run}, see clickhouse.sql):
 * written once a run has ended, by the archiver, and read for every run that is no longer in the
 * run store. A run is one row; one written twice is one row once ClickHouse has merged, and the
 * reads take the latest per run without waiting for that.
 *
 * <p>Times are stored as {@code DateTime64} and read as epoch milliseconds, the epoch meaning "does
 * not apply", so no column is nullable.
 */
@Component
public class RunHistory {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(60);

    private static final String COLUMNS = "run_id, flow_id, flow_external_id, data_set_id, version, status, "
            + "attempt, max_attempts, preview, trigger, triggered_by, run_as, parameters, processor_versions, "
            + "error_code, error, stats, toUnixTimestamp64Milli(window_start) AS window_start_ms, "
            + "toUnixTimestamp64Milli(window_end) AS window_end_ms, toUnixTimestamp64Milli(created_at) AS created_ms, "
            + "toUnixTimestamp64Milli(started_at) AS started_ms, toUnixTimestamp64Milli(finished_at) AS finished_ms";

    private final Function<String, Client> reader;
    private final Function<String, Client> writer;

    @Autowired
    public RunHistory(ClickHouseClientPool pool, TenantConfigService tenants) {
        this(tenantId -> pool.getReadOnlyClient(tenants.getConfig(tenantId)),
                tenantId -> pool.getClient(tenants.getConfig(tenantId)));
    }

    RunHistory(Function<String, Client> reader, Function<String, Client> writer) {
        this.reader = reader;
        this.writer = writer;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes finished runs, one insert, deduplicated on the set of runs it holds so that the same
     * batch inserted twice, because its archiver died before saying so, is stored once.
     */
    public void insert(String tenantId, List<StoredRun> runs) throws Exception {
        if (runs.isEmpty()) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (StoredRun r : runs) {
            out.writeBytes(JSON.writeValueAsBytes(row(r)));
            out.write('\n');
        }
        InsertSettings settings = new InsertSettings().setDeduplicationToken(token(runs))
                .serverSetting("async_insert", "0");
        try (var response = writer.apply(tenantId).insert("flow_run", columns(), new ByteArrayInputStream(out.toByteArray()),
                ClickHouseFormat.JSONEachRow, settings).get(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            // Nothing to read from an insert.
        }
    }

    private static List<String> columns() {
        return List.of("run_id", "flow_id", "flow_external_id", "data_set_id", "version", "status", "attempt",
                "max_attempts", "preview", "trigger", "triggered_by", "run_as", "window_start", "window_end",
                "parameters", "processor_versions", "created_at", "started_at", "finished_at", "error_code",
                "error", "stats");
    }

    private static ObjectNode row(StoredRun r) {
        ObjectNode row = JSON.createObjectNode();
        row.put("run_id", r.id().toString());
        row.put("flow_id", r.flowId() == null ? 0 : r.flowId());
        row.put("flow_external_id", text(r.flowExternalId()));
        row.put("data_set_id", r.dataSetId() == null ? 0 : r.dataSetId());
        row.put("version", r.version() == null ? 0 : r.version());
        row.put("status", r.status());
        row.put("attempt", r.attempt());
        row.put("max_attempts", r.maxAttempts());
        row.put("preview", r.preview());
        row.put("trigger", text(r.trigger()));
        row.put("triggered_by", text(r.triggeredBy()));
        row.put("run_as", text(r.runAs()));
        row.put("window_start", time(r.window() == null ? null : r.window().start()));
        row.put("window_end", time(r.window() == null ? null : r.window().end()));
        row.put("parameters", text(r.parameters()));
        row.put("processor_versions", text(r.processorVersions()));
        row.put("created_at", time(r.createdAt()));
        row.put("started_at", time(r.startedAt()));
        row.put("finished_at", time(r.finishedAt()));
        row.put("error_code", text(r.errorCode()));
        row.put("error", text(r.error()));
        row.put("stats", text(r.stats()));
        return row;
    }

    private static String token(List<StoredRun> runs) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            runs.stream().map(r -> r.id().toString()).sorted()
                    .forEach(id -> sha.update(id.getBytes(StandardCharsets.UTF_8)));
            StringBuilder hex = new StringBuilder("runs:");
            for (byte b : sha.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- reading

    public Optional<StoredRun> find(String tenantId, UUID id) {
        return query(tenantId, "SELECT " + COLUMNS + " FROM flow_run WHERE run_id = {id:UUID} "
                + "ORDER BY finished_at DESC LIMIT 1", Map.of("id", id.toString())).stream().findFirst();
    }

    /**
     * Finished runs, newest first, in these datasets (all when null) and optionally of one flow, after
     * the run created at {@code beforeCreated} with id {@code beforeId}. Previews only with
     * {@code previews}.
     */
    public List<StoredRun> list(String tenantId, Collection<Long> dataSets, Long flowId, boolean previews,
                                Instant beforeCreated, UUID beforeId, int limit) {
        if (dataSets != null && dataSets.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM flow_run WHERE true");
        Map<String, Object> params = new HashMap<>();
        if (dataSets != null) {
            sql.append(" AND data_set_id IN (").append(dataSets.stream().map(String::valueOf)
                    .collect(Collectors.joining(","))).append(")");
        }
        if (flowId != null) {
            sql.append(" AND flow_id = {flow:UInt64}");
            params.put("flow", flowId);
        }
        if (!previews) {
            sql.append(" AND NOT preview");
        }
        if (beforeCreated != null) {
            sql.append(" AND (created_at, run_id) < ({created:DateTime64(3, 'UTC')}, {before:UUID})");
            params.put("created", TIME.format(beforeCreated));
            params.put("before", beforeId.toString());
        }
        sql.append(" ORDER BY created_at DESC, run_id DESC, finished_at DESC LIMIT 1 BY run_id LIMIT ").append(limit);
        return query(tenantId, sql.toString(), params);
    }

    /** The runs among {@code ids} of this flow that failed for good, for re-running them. */
    public List<StoredRun> failed(String tenantId, long flowId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String in = ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        return query(tenantId, "SELECT " + COLUMNS + " FROM flow_run WHERE flow_id = {flow:UInt64} AND NOT preview "
                + "AND run_id IN (" + in + ") ORDER BY run_id, finished_at DESC LIMIT 1 BY run_id",
                Map.of("flow", flowId)).stream().filter(r -> "FAILED".equals(r.status())).toList();
    }

    private List<StoredRun> query(String tenantId, String sql, Map<String, Object> params) {
        List<StoredRun> out = new ArrayList<>();
        for (GenericRecord r : reader.apply(tenantId).queryAll(sql, params)) {
            Long flowId = r.getLong("flow_id");
            long windowStart = r.getLong("window_start_ms");
            out.add(new StoredRun(r.getUUID("run_id"), flowId == 0 ? null : flowId,
                    blankToNull(r.getString("flow_external_id")), r.getLong("data_set_id"),
                    (int) r.getLong("version"), r.getString("status"), r.getInteger("attempt"),
                    r.getInteger("max_attempts"), null, false, r.getBoolean("preview"), false, Duration.ZERO, null,
                    windowStart == 0 ? null : new TimeWindow(Instant.ofEpochMilli(windowStart),
                            Instant.ofEpochMilli(r.getLong("window_end_ms"))),
                    blankToNull(r.getString("parameters")), blankToNull(r.getString("processor_versions")), null,
                    blankToNull(r.getString("trigger")), blankToNull(r.getString("triggered_by")),
                    blankToNull(r.getString("run_as")), instant(r.getLong("created_ms")),
                    instant(r.getLong("started_ms")), instant(r.getLong("finished_ms")),
                    blankToNull(r.getString("error_code")), blankToNull(r.getString("error")),
                    blankToNull(r.getString("stats"))));
        }
        return out;
    }

    private static Instant instant(long millis) {
        return millis == 0 ? null : Instant.ofEpochMilli(millis);
    }

    private static String time(Instant instant) {
        return TIME.format(instant == null ? Instant.EPOCH : instant);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
