// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.responses.StatsCounts;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantConfigService;
import com.clickhouse.client.api.Client;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ClickHouse reads that back ONLY the home dashboard's stat widgets. This lives in its own
 * dashboard-owned service — rather than as methods bolted onto {@link ClickHouseEventService} /
 * {@link ClickHouseDatapointService} — so the dashboard depends on the shared ClickHouse layer
 * without modifying it: it can't couple to, conflict with, or break those core services. It reuses
 * the shared tenant-scoped client pool via {@link ClickHouseService}; every query runs against the
 * current {@code TenantContext}. Queries are read-only counts/aggregations over existing tables.
 */
@Service
@Slf4j
public class ClickHouseDashboardService extends ClickHouseService {

    public ClickHouseDashboardService(TenantConfigService tenantConfigService,
                                      ValkeyService valkeyService,
                                      ClickHouseClientPool clickHouseClientPool) {
        super(tenantConfigService, valkeyService, clickHouseClientPool);
    }

    /** Total events for the current tenant. */
    public long countEvents() {
        AtomicLong count = new AtomicLong();
        Client client = getClickhouseClient();
        client.queryAll("SELECT count(1) as count FROM events").forEach(r -> count.set(r.getLong("count")));
        return count.get();
    }

    /** Count events of one type for the current tenant (case-insensitive, e.g. "Alarm"). */
    public long countEventsByType(String type) {
        AtomicLong count = new AtomicLong();
        String query = "SELECT count(1) as count FROM events WHERE lower(type) = lower({type:String})";
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);

        Client client = getClickhouseClient();
        client.queryAll(query, params).forEach(r -> count.set(r.getLong("count")));
        return count.get();
    }

    /**
     * Top event types among the tenant's most recent {@code recentN} events, largest first — with
     * NO calendar window. Reflects the tenant's actual recent activity regardless of absolute date,
     * so the dashboard's event-type widget isn't empty just because the newest events happen to be
     * older than a fixed window. Data-driven, so each tenant sees its own domain categories
     * (incidents, work permits, purchase orders, ...) rather than fixed rows.
     */
    public List<StatsCounts.EventTypeCount> topEventTypesRecent(int recentN, int topN) {
        List<StatsCounts.EventTypeCount> out = new ArrayList<>();
        String query = "SELECT type, count(1) as cnt FROM " +
                "(SELECT type FROM events ORDER BY event_time DESC LIMIT {recent:Int32}) " +
                "GROUP BY type ORDER BY cnt DESC LIMIT {lim:Int32}";
        Map<String, Object> params = new HashMap<>();
        params.put("recent", recentN);
        params.put("lim", topN);

        Client client = getClickhouseClient();
        client.queryAll(query, params).forEach(r ->
                out.add(new StatsCounts.EventTypeCount(r.getString("type"), r.getLong("cnt"))));
        return out;
    }

    /**
     * Total datapoints across all the tenant's {@code datapoints_*} tables, read from ClickHouse
     * table metadata ({@code system.tables.total_rows}) in a single query — no per-table row scan.
     * The count is approximate for a {@code ReplacingMergeTree} (counts pre-merge rows) but is exact
     * enough for a dashboard tile and effectively free.
     */
    public long countAllDatapoints() {
        AtomicLong total = new AtomicLong();
        try {
            Client client = getClickhouseClient();
            client.queryAll("SELECT toInt64(ifNull(sum(total_rows), 0)) AS count FROM system.tables " +
                    "WHERE database = currentDatabase() AND name LIKE 'datapoints\\_%'")
                    .forEach(r -> total.set(r.getLong("count")));
        } catch (Exception e) {
            log.warn("datapoint count failed: {}", e.getMessage());
        }
        return total.get();
    }
}
