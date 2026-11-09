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
 * <em>Retention</em>): the history of finished runs in each tenant's ClickHouse ({@code flow_run})
 * and the records of what they did ({@code flow_run_event}), both by the table's TTL. So a record
 * never outlives its run, and a run never shows records that already expired. Runs that are queued,
 * running or just ended are in the tenant's KVRocks, which expires them itself.
 *
 * <p>It lives here rather than in the flow service because it is housekeeping and wants one janitor:
 * the cleanup app runs as a single instance and already holds each tenant's ClickHouse, and owning
 * both tables keeps the one period in one property. The flow service runs no deletes of its own.
 *
 * <p>The TTL is altered only when it differs from the period: an {@code ALTER ... MODIFY TTL} makes
 * ClickHouse rewrite the table's parts, which is not something to do every night.
 *
 * <p>The period is one for every tenant ({@code datahub.flow.retention}, default 90 days), set by
 * the operator. A per-tenant period would go with the other operator limits when they move into a
 * tenant's configuration; nothing here assumes there is only one.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "datahub.flow.retention-enabled", havingValue = "true", matchIfMissing = true)
public class FlowRetentionTask {

    /** The tables, and the time column each expires by. */
    static final Map<String, String> TABLES = Map.of("flow_run_event", "event_time", "flow_run", "finished_at");

    private final TenantConfigService tenants;
    private final Function<Tenant, Client> clickhouse;
    private final Duration retention;

    @Autowired
    public FlowRetentionTask(TenantConfigService tenants, ClickHouseClientPool clickhouse,
                             @Value("${datahub.flow.retention:P90D}") Duration retention) {
        this(tenants, clickhouse::getClient, retention);
    }

    FlowRetentionTask(TenantConfigService tenants, Function<Tenant, Client> clickhouse, Duration retention) {
        if (retention.toDays() < 1) {
            throw new IllegalStateException("datahub.flow.retention must be at least a day, not "
                    + retention + ": ClickHouse's TTL counts whole days.");
        }
        this.tenants = tenants;
        this.clickhouse = clickhouse;
        this.retention = retention;
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

    /** Both tables for one tenant. */
    void expire(Tenant tenant) {
        Client client = clickhouse.apply(tenant);
        TABLES.forEach((table, column) -> alignTtl(client, tenant.getOrganizationId(), table, column));
    }

    /** Sets the table's TTL to the period, when it is not already; true when it changed it. */
    boolean alignTtl(Client client, String tenantId, String table, String column) {
        List<GenericRecord> rows = client.queryAll("SELECT create_table_query FROM system.tables "
                + "WHERE database = currentDatabase() AND name = '" + table + "'");
        if (rows.isEmpty()) {
            log.debug("Tenant {} has no {} table yet", tenantId, table);
            return false;
        }
        long days = retention.toDays();
        Matcher ttl = Pattern.compile("TTL\\s+toDateTime\\(" + column + "\\)\\s*\\+\\s*toIntervalDay\\((\\d+)\\)")
                .matcher(rows.getFirst().getString("create_table_query"));
        if (ttl.find() && Long.parseLong(ttl.group(1)) == days) {
            return false;
        }
        try (QueryResponse ignored = client.query("ALTER TABLE " + table
                + " MODIFY TTL toDateTime(" + column + ") + INTERVAL " + days + " DAY")
                .get(10, TimeUnit.MINUTES)) {
            log.info("Set {} to expire after {} days for tenant {}", table, days, tenantId);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Could not set the TTL of " + table + " for tenant "
                    + tenantId + ": " + e.getMessage(), e);
        }
    }
}
