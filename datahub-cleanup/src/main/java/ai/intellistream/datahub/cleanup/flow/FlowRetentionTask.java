// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.flow;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What intellistream-flow leaves behind expires together, after one retention period (the plan's
 * <em>Retention</em>): finished run rows in each tenant's Postgres ({@code flow.run}), and the
 * records in its ClickHouse ({@code flow_run_event}), by the table's TTL. So a record never
 * outlives its run, and a run never shows records that already expired.
 *
 * <p>It lives here rather than in the flow service because both halves are housekeeping and want
 * one janitor: the cleanup app runs as a single instance, already holds each tenant's Postgres and
 * ClickHouse, and owning both halves keeps the one period in one property. The flow service runs
 * no deletes of its own.
 *
 * <ul>
 *   <li><b>Run rows</b> are deleted in batches, each its own statement, so no single delete holds
 *       many rows or a long transaction behind pgbouncer. A run still owing its lifecycle event
 *       (it failed for good and the event did not get written yet) is kept until the event is.</li>
 *   <li><b>The TTL</b> is altered only when it differs from the period: an {@code ALTER ... MODIFY
 *       TTL} makes ClickHouse rewrite the table's parts, which is not something to do every
 *       night.</li>
 * </ul>
 *
 * <p>The period is one for every tenant ({@code datahub.flow.retention}, default 90 days), set by
 * the operator. A per-tenant period would go with the other operator limits when they move into a
 * tenant's configuration; nothing here assumes there is only one.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "datahub.flow.retention-enabled", havingValue = "true", matchIfMissing = true)
public class FlowRetentionTask {

    static final String TABLE = "flow_run_event";
    private static final Pattern TTL_DAYS = Pattern.compile(
            "TTL\\s+toDateTime\\(event_time\\)\\s*\\+\\s*toIntervalDay\\((\\d+)\\)");

    private final TenantConfigService tenants;
    private final JdbcTemplate jdbc;
    private final Function<Tenant, Client> clickhouse;
    private final Duration retention;
    private final int batchSize;

    @Autowired
    public FlowRetentionTask(TenantConfigService tenants, JdbcTemplate jdbc, ClickHouseClientPool clickhouse,
                             @Value("${datahub.flow.retention:P90D}") Duration retention,
                             @Value("${datahub.flow.retention-batch-size:1000}") int batchSize) {
        this(tenants, jdbc, clickhouse::getClient, retention, batchSize);
    }

    FlowRetentionTask(TenantConfigService tenants, JdbcTemplate jdbc, Function<Tenant, Client> clickhouse,
                      Duration retention, int batchSize) {
        if (retention.toDays() < 1) {
            throw new IllegalStateException("datahub.flow.retention must be at least a day, not "
                    + retention + ": ClickHouse's TTL counts whole days.");
        }
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.clickhouse = clickhouse;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    // Default: 04:30 every day. Override with datahub.flow.retention-cron.
    @Scheduled(cron = "${datahub.flow.retention-cron:0 30 4 * * *}")
    public void expire() {
        Map<String, Tenant> registry = tenants.cachedTenants;
        if (registry == null) {
            return;
        }
        for (Tenant tenant : registry.values()) {
            if (tenant.getFeatures() == null || !tenant.getFeatures().isFlowFeatureEnabled()) {
                continue;
            }
            try {
                TenantContext.runWith(tenant.getOrganizationId(), () -> expire(tenant));
            } catch (RuntimeException e) {
                // One tenant's failure must not stop the rest.
                log.error("Flow retention failed for tenant {}: {}", tenant.getOrganizationId(),
                        e.getMessage(), e);
            }
        }
    }

    /** Both halves for one tenant, under its {@code TenantContext}. */
    void expire(Tenant tenant) {
        int deleted = deleteExpiredRuns();
        if (deleted > 0) {
            log.info("Deleted {} flow runs older than {} days for tenant {}", deleted,
                    retention.toDays(), tenant.getOrganizationId());
        }
        alignTtl(clickhouse.apply(tenant), tenant.getOrganizationId());
    }

    /** Deletes finished runs older than the period, a batch at a time; how many. */
    int deleteExpiredRuns() {
        if (jdbc.queryForObject("SELECT to_regclass('flow.run') IS NOT NULL", Boolean.class) != Boolean.TRUE) {
            return 0;
        }
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM flow.run WHERE id IN (SELECT id FROM flow.run "
                            + "WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') "
                            + "AND finished_at < now() - make_interval(days => ?) "
                            + "AND NOT failure_event_owed LIMIT ?)",
                    (int) retention.toDays(), batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }

    /** Sets the records table's TTL to the period, when it is not already; true when it changed it. */
    boolean alignTtl(Client client, String tenantId) {
        List<GenericRecord> rows = client.queryAll("SELECT create_table_query FROM system.tables "
                + "WHERE database = currentDatabase() AND name = '" + TABLE + "'");
        if (rows.isEmpty()) {
            log.debug("Tenant {} has no {} table yet", tenantId, TABLE);
            return false;
        }
        long days = retention.toDays();
        Matcher ttl = TTL_DAYS.matcher(rows.getFirst().getString("create_table_query"));
        if (ttl.find() && Long.parseLong(ttl.group(1)) == days) {
            return false;
        }
        try (QueryResponse ignored = client.query("ALTER TABLE " + TABLE
                + " MODIFY TTL toDateTime(event_time) + INTERVAL " + days + " DAY")
                .get(10, TimeUnit.MINUTES)) {
            log.info("Set {} to expire records after {} days for tenant {}", TABLE, days, tenantId);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Could not set the TTL of " + TABLE + " for tenant "
                    + tenantId + ": " + e.getMessage(), e);
        }
    }
}
