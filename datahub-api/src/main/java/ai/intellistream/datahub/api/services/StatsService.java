// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.responses.StatsCounts;
import ai.intellistream.datahub.clickhouse.ClickHouseDashboardService;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.PolicyRepository;
import ai.intellistream.datahub.repositories.node.ResourceRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Home-dashboard stats for the current tenant, cached PER METRIC in that tenant's Valkey.
 *
 * <p>Each metric is its own key with its own TTL, so metrics refresh at the cadence that fits them:
 * structural counts (assets/time-series/datasets/policies) rarely change and get a long TTL, {@code
 * alarms}/{@code topEventTypes} get a short one, and {@code datapoints}/{@code events} get the
 * shortest of all — they're a thin cache in front of their own {@link LiveIngestCounter}'s live,
 * continuously-incremented Valkey counter, not a recomputation, so there's no cost to refreshing on
 * every poll. A request reads the wanted keys in a single MGET and recomputes only the expired ones
 * — so a fast poll for events doesn't drag the slow structural counts along, and callers can ask for
 * just the subset they need. No scheduler (that would recompute in every instance); the TTL drives
 * refresh on demand.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final String PREFIX = "dh:stats:";  // tenant-scoped keys: dh:stats:<tenant>:<metric>
    private static final long TTL_SLOW = 300;          // structural counts — rarely change
    private static final long TTL_FAST = 60;           // activity counts — change often
    private static final long TTL_LIVE = 2;            // datapoints/events — thin cache over an already-live counter

    /** Every metric the dashboard knows about; also the default set when a caller asks for none. */
    public static final List<String> ALL_METRICS = List.of(
            "rootAssets", "allAssets", "timeseries", "datasets", "policies",
            "events", "alarms", "datapoints", "topEventTypes");

    private final ResourceRepository resourceRepository;
    private final TimeseriesRepository timeseriesRepository;
    private final DataSetRepository dataSetRepository;
    private final PolicyRepository policyRepository;
    private final ClickHouseDashboardService dashboardClickHouse;
    @Qualifier("datapointIngestCounter")
    private final LiveIngestCounter datapointIngestCounter;
    @Qualifier("eventIngestCounter")
    private final LiveIngestCounter eventIngestCounter;
    private final ValkeyService valkey;
    private final JsonMapper jsonMapper;

    /**
     * Return the requested metrics (or all, if {@code requested} is null/empty) for the current
     * tenant. Each is served from its Valkey key; expired/missing ones are recomputed once and
     * re-cached at that metric's TTL. Unknown metric names are ignored.
     */
    public Map<String, Object> getStats(Collection<String> requested) {
        List<String> metrics = (requested == null || requested.isEmpty())
                ? ALL_METRICS
                : requested.stream().filter(ALL_METRICS::contains).distinct().toList();
        if (metrics.isEmpty()) return Map.of();

        String tenant = TenantContext.getTenantId();
        List<String> keys = metrics.stream().map(m -> key(tenant, m)).toList();

        Map<String, String> cached;
        try {
            cached = valkey.multiGet(keys);
        } catch (Exception e) {
            log.warn("stats: valkey mget failed, recomputing: {}", e.getMessage());
            cached = Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (String m : metrics) {
            String raw = cached.get(key(tenant, m));
            if (raw == null) {
                raw = computeOne(m);
                try {
                    valkey.setString(key(tenant, m), raw, ttlOf(m));
                } catch (Exception e) {
                    log.warn("stats: valkey store failed for {}: {}", m, e.getMessage());
                }
            }
            out.put(m, decode(m, raw));
        }
        return out;
    }

    private static String key(String tenant, String metric) {
        return PREFIX + tenant + ":" + metric;
    }

    private static long ttlOf(String metric) {
        return switch (metric) {
            case "datapoints", "events" -> TTL_LIVE;
            case "alarms", "topEventTypes" -> TTL_FAST;
            default -> TTL_SLOW;
        };
    }

    /** Compute one metric from the source-of-truth stores, serialized to a String for Valkey. */
    private String computeOne(String metric) {
        return switch (metric) {
            case "rootAssets" -> String.valueOf(resourceRepository.countByIsRootTrue());
            case "allAssets" -> String.valueOf(resourceRepository.count());
            case "timeseries" -> String.valueOf(timeseriesRepository.count());
            case "datasets" -> String.valueOf(dataSetRepository.count());
            case "policies" -> String.valueOf(policyRepository.count());
            case "alarms" -> String.valueOf(dashboardClickHouse.countEventsByType("Alarm"));
            // Thin cache (TTL_LIVE) over each metric's own LiveIngestCounter, not a ClickHouse
            // recomputation — see the class javadoc.
            case "datapoints" -> String.valueOf(datapointIngestCounter.getCurrentTotal());
            case "events" -> String.valueOf(eventIngestCounter.getCurrentTotal());
            // Top event types among the most recent events (no calendar window) so the widget reflects
            // real activity even when the newest events are older than a week.
            case "topEventTypes" -> jsonMapper.writeValueAsString(dashboardClickHouse.topEventTypesRecent(200, 5));
            default -> "0";
        };
    }

    /** Turn a cached String back into the JSON value the endpoint returns (a number, or the list). */
    private Object decode(String metric, String raw) {
        if ("topEventTypes".equals(metric)) {
            try {
                return Arrays.asList(jsonMapper.readValue(raw, StatsCounts.EventTypeCount[].class));
            } catch (Exception e) {
                return List.of();
            }
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
