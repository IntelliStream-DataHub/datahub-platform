// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * The limits in force for the request's tenant: the {@code datahub.limits.*} defaults with that
 * tenant's {@code tenant_limits} row applied over them.
 *
 * <p>Cached in-process for a few minutes, so a limit that is consulted on every request costs one
 * query per tenant per instance per TTL rather than a round trip per call. The TTL is also the
 * propagation delay: an operator raising a limit for a tenant that has hit it sees it take effect
 * across every instance within that window, with no restart and no cache to invalidate by hand.
 *
 * <p>Any failure resolves to the defaults rather than propagating. This is consulted from a servlet
 * filter that runs before tenant provisioning, so a first-touch request legitimately arrives before
 * the schema exists — and a limits lookup that cannot answer is never a reason to refuse a request
 * that is otherwise fine.
 */
@Slf4j
@Service
public class TenantLimitsService {

    private final LimitsProperties defaults;
    private final JdbcTemplate jdbcTemplate;
    private final long ttlMillis;
    private final LongSupplier clock;

    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(TenantLimits limits, long resolvedAtMillis) {
    }

    // Explicit, because the test constructor below makes this an ambiguous choice otherwise.
    @Autowired
    public TenantLimitsService(LimitsProperties defaults,
                               JdbcTemplate jdbcTemplate,
                               @Value("${datahub.limits.cache-ttl:5m}") Duration cacheTtl) {
        this(defaults, jdbcTemplate, cacheTtl, System::currentTimeMillis);
    }

    /** Test seam: a clock that can be advanced, so the cache TTL is assertable without sleeping. */
    TenantLimitsService(LimitsProperties defaults,
                        JdbcTemplate jdbcTemplate,
                        Duration cacheTtl,
                        LongSupplier clock) {
        this.defaults = defaults;
        this.jdbcTemplate = jdbcTemplate;
        this.ttlMillis = cacheTtl.toMillis();
        this.clock = clock;
    }

    /** The limits for the tenant on this thread, or the deployment defaults if there is none. */
    public TenantLimits current() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return fromDefaults();
        }
        return forTenant(tenantId);
    }

    public TenantLimits forTenant(String tenantId) {
        long now = clock.getAsLong();
        Entry cached = cache.get(tenantId);
        if (cached != null && now - cached.resolvedAtMillis() < ttlMillis) {
            return cached.limits();
        }
        TenantLimits resolved = load(tenantId);
        cache.put(tenantId, new Entry(resolved, now));
        return resolved;
    }

    private TenantLimits load(String tenantId) {
        try {
            TenantLimits row = jdbcTemplate.query(
                    "SELECT * FROM tenant_limits WHERE id = 1",
                    this::mapRow);
            return row == null ? fromDefaults() : row;
        } catch (RuntimeException e) {
            // Includes the tenant whose schema is not provisioned yet: the rate-limit filter runs
            // before provisioning, so this is expected on a first touch rather than exceptional.
            log.debug("No tenant_limits for tenant {} ({}); using deployment defaults.",
                    tenantId, e.getClass().getSimpleName());
            return fromDefaults();
        }
    }

    private TenantLimits mapRow(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return null;
        }
        TenantLimits base = fromDefaults();
        return new TenantLimits(
                intOr(rs, "write_per_minute_per_tenant", base.writePerMinutePerTenant()),
                intOr(rs, "read_per_minute_per_tenant", base.readPerMinutePerTenant()),
                intOr(rs, "write_per_minute_per_user", base.writePerMinutePerUser()),
                intOr(rs, "read_per_minute_per_user", base.readPerMinutePerUser()),
                longOr(rs, "events_per_day", base.eventsPerDay()),
                longOr(rs, "nodes_per_day", base.nodesPerDay()),
                longOr(rs, "edges_per_day", base.edgesPerDay()),
                longOr(rs, "datapoints_per_day", base.datapointsPerDay()),
                longOr(rs, "ingest_bytes_per_day", base.ingestBytesPerDay()),
                longOr(rs, "max_resources", base.maxResources()),
                longOr(rs, "max_events_total", base.maxEventsTotal()),
                longOr(rs, "max_datapoints_total", base.maxDatapointsTotal()),
                longOr(rs, "max_text_datapoints_total", base.maxTextDatapointsTotal()),
                intOr(rs, "max_ws_sockets_per_tenant", base.maxWsSocketsPerTenant()),
                intOr(rs, "max_ws_sockets_per_user", base.maxWsSocketsPerUser()));
    }

    /** A NULL column means "inherit"; the column being absent entirely means an older schema. */
    private static int intOr(ResultSet rs, String column, int fallback) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? fallback : value;
    }

    private static long longOr(ResultSet rs, String column, long fallback) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? fallback : value;
    }

    /**
     * The deployment defaults. A whole section switched off (via its {@code enabled} flag) resolves
     * to 0 across that section, which every check reads as unlimited.
     */
    private TenantLimits fromDefaults() {
        LimitsProperties.Rate rate = defaults.getRate();
        LimitsProperties.Quota quota = defaults.getQuota();
        LimitsProperties.Lifetime lifetime = defaults.getLifetime();
        LimitsProperties.WebSocket websocket = defaults.getWebsocket();
        boolean quotas = quota.isEnabled();
        boolean ceilings = lifetime.isEnabled();
        boolean sockets = websocket.isEnabled();
        return new TenantLimits(
                rate.getWritePerMinutePerTenant(),
                rate.getReadPerMinutePerTenant(),
                rate.getWritePerMinutePerUser(),
                rate.getReadPerMinutePerUser(),
                quotas ? quota.getEventsPerDay() : 0,
                quotas ? quota.getNodesPerDay() : 0,
                quotas ? quota.getEdgesPerDay() : 0,
                quotas ? quota.getDatapointsPerDay() : 0,
                quotas ? quota.getIngestBytesPerDay() : 0,
                ceilings ? lifetime.getMaxResources() : 0,
                ceilings ? lifetime.getMaxEventsTotal() : 0,
                ceilings ? lifetime.getMaxDatapointsTotal() : 0,
                ceilings ? lifetime.getMaxTextDatapointsTotal() : 0,
                sockets ? websocket.getMaxSocketsPerTenant() : 0,
                sockets ? websocket.getMaxSocketsPerUser() : 0);
    }
}
