// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.IngestQuotaExceededException;
import ai.intellistream.datahub.api.controllers.errors.TenantLimitReachedException;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * How much a tenant may ingest: a rolling daily allowance, and a lifetime ceiling.
 *
 * <p>The two answer different questions. The daily quota is a rate — spend it and it returns at
 * midnight — and is what stops a caller turning steady, individually legal requests into bulk
 * storage. The lifetime ceiling is the size of the sandbox a free tenant gets, and only moves when
 * someone raises it.
 *
 * <p>Counted the way {@link LiveIngestCounter} counts: accumulated in memory per tenant and flushed
 * to Valkey every couple of seconds, so a per-request check costs no round trip. The cost is that a
 * tenant can overshoot by whatever the other instances ingest inside one flush interval. That is the
 * right trade here — these are ceilings, not invoices — and the overshoot is bounded by the interval
 * rather than growing with traffic.
 *
 * <p>Enforced in the service layer rather than in a filter, because the MCP tools call the services
 * directly and would otherwise be uncounted. Bytes are the exception: they are charged by the
 * request-size filter, which is where the size of a body is known.
 *
 * <p>A Valkey failure lets ingest through. Refusing writes because a counter is unreachable would
 * turn a cache outage into an ingest outage.
 */
@Slf4j
@Service
public class IngestQuotaService {

    private static final long FLUSH_MS = 2000;

    /** Two days, so a window's key outlives the window even with clock skew between instances. */
    private static final long DAILY_KEY_TTL_SECONDS = 2 * 24 * 3600;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /**
     * What is counted. Nodes share one budget because resources, timeseries, datasets, labels,
     * policies and functions are all rows in the same table: separate budgets would be six doors
     * into the same room.
     */
    public enum QuotaMetric {
        EVENTS("events"),
        NODES("nodes"),
        EDGES("relationships"),
        DATAPOINTS("data points"),
        /** Charged alongside DATAPOINTS for a TEXT/MIXED series, which is the expensive kind. */
        TEXT_DATAPOINTS("text data points"),
        BYTES("ingested bytes");

        private final String label;

        QuotaMetric(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        String key() {
            return name().toLowerCase();
        }
    }

    /** One Valkey counter. {@code daily} decides whether it expires and which limit it answers to. */
    private record Counter(String tenantId, String valkeyKey, boolean daily) {
    }

    private final TenantLimitsService tenantLimits;
    private final ValkeyService valkeyService;
    private final Supplier<Instant> clock;

    /** Counted but not yet flushed. */
    private final Map<Counter, AtomicLong> pending = new ConcurrentHashMap<>();

    /** The last total Valkey reported, so a check costs no round trip of its own. */
    private final Map<String, Long> known = new ConcurrentHashMap<>();

    // Explicit, because the test constructor below makes this an ambiguous choice otherwise.
    @Autowired
    public IngestQuotaService(TenantLimitsService tenantLimits, ValkeyService valkeyService) {
        this(tenantLimits, valkeyService, Instant::now);
    }

    /** Test seam: a clock that can be moved across a day boundary. */
    IngestQuotaService(TenantLimitsService tenantLimits, ValkeyService valkeyService, Supplier<Instant> clock) {
        this.tenantLimits = tenantLimits;
        this.valkeyService = valkeyService;
        this.clock = clock;
    }

    /**
     * Charge {@code count} to the current tenant, refusing if that would take it past either
     * ceiling. Call after validation and authorization, so a request that was going to fail anyway
     * does not spend the tenant's allowance.
     *
     * @throws IngestQuotaExceededException if the daily allowance is spent (429, retryable)
     * @throws TenantLimitReachedException  if the lifetime ceiling is reached (403, not retryable)
     */
    public void checkAndRecord(QuotaMetric metric, long count) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || count <= 0) {
            return;
        }
        TenantLimits limits = tenantLimits.forTenant(tenantId);

        long dailyLimit = dailyLimit(limits, metric);
        Counter daily = dailyCounter(tenantId, metric);
        if (!TenantLimits.unlimited(dailyLimit) && used(daily) + count > dailyLimit) {
            throw new IngestQuotaExceededException(metric.label(), dailyLimit, secondsUntilUtcMidnight());
        }

        long lifetimeLimit = lifetimeLimit(limits, metric);
        Counter lifetime = lifetimeCounter(tenantId, metric);
        if (!TenantLimits.unlimited(lifetimeLimit) && used(lifetime) + count > lifetimeLimit) {
            throw new TenantLimitReachedException(metric.label(), lifetimeLimit);
        }

        // Only what is actually bounded is counted: the daily counter is the rate, the lifetime one
        // the running total, and a metric with neither limit set costs no Valkey traffic at all.
        if (!TenantLimits.unlimited(dailyLimit)) {
            add(daily, count);
        }
        if (!TenantLimits.unlimited(lifetimeLimit)) {
            add(lifetime, count);
        }
    }

    /** The tenant's lifetime total for a metric, as far as this instance knows it. */
    public long lifetimeTotal(QuotaMetric metric) {
        String tenantId = TenantContext.getTenantId();
        return tenantId == null ? 0 : used(lifetimeCounter(tenantId, metric));
    }

    private void add(Counter counter, long count) {
        pending.computeIfAbsent(counter, k -> new AtomicLong()).addAndGet(count);
    }

    /**
     * What this tenant has spent: the last flushed total plus what this instance is holding. The
     * local part matters — without it a burst inside one flush interval reads a stale total and
     * sails past the ceiling.
     */
    private long used(Counter counter) {
        long flushed = known.computeIfAbsent(counter.valkeyKey(), this::readTotal);
        AtomicLong local = pending.get(counter);
        return flushed + (local == null ? 0 : local.get());
    }

    private long readTotal(String key) {
        try {
            String raw = valkeyService.getString(key);
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (RuntimeException e) {
            log.debug("Could not read quota counter {}: {}", key, e.toString());
            return 0L;
        }
    }

    /**
     * Push what has accumulated to Valkey. The reply is the cluster-wide total and becomes what the
     * next check reads, so instances counting the same tenant converge within an interval.
     */
    @Scheduled(fixedRate = FLUSH_MS)
    void flush() {
        for (Map.Entry<Counter, AtomicLong> entry : pending.entrySet()) {
            long delta = entry.getValue().getAndSet(0);
            if (delta <= 0) {
                continue;
            }
            Counter counter = entry.getKey();
            try {
                TenantContext.runWith(counter.tenantId(), () -> {
                    long total = counter.daily()
                            ? valkeyService.incrementAndExpireIfNew(counter.valkeyKey(), delta, DAILY_KEY_TTL_SECONDS)
                            : valkeyService.increment(counter.valkeyKey(), delta);
                    known.put(counter.valkeyKey(), total);
                });
            } catch (Exception e) {
                log.warn("Quota flush failed for {}, retrying next interval: {}",
                        counter.valkeyKey(), e.getMessage());
                // Fold the delta back in rather than losing it to a transient Valkey failure.
                entry.getValue().addAndGet(delta);
            }
        }
    }

    private Counter dailyCounter(String tenantId, QuotaMetric metric) {
        return new Counter(tenantId,
                "dh:quota:%s:%s:%s".formatted(metric.key(), tenantId, DAY.format(clock.get())),
                true);
    }

    private Counter lifetimeCounter(String tenantId, QuotaMetric metric) {
        return new Counter(tenantId, "dh:quota:total:%s:%s".formatted(metric.key(), tenantId), false);
    }

    private long secondsUntilUtcMidnight() {
        Instant now = clock.get();
        Instant midnight = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return Math.max(1, midnight.getEpochSecond() - now.getEpochSecond());
    }

    private static long dailyLimit(TenantLimits limits, QuotaMetric metric) {
        return switch (metric) {
            case EVENTS -> limits.eventsPerDay();
            case NODES -> limits.nodesPerDay();
            case EDGES -> limits.edgesPerDay();
            case DATAPOINTS -> limits.datapointsPerDay();
            case BYTES -> limits.ingestBytesPerDay();
            // Covered by the DATAPOINTS allowance; this metric exists for its lifetime ceiling.
            case TEXT_DATAPOINTS -> 0;
        };
    }

    private static long lifetimeLimit(TenantLimits limits, QuotaMetric metric) {
        return switch (metric) {
            case EVENTS -> limits.maxEventsTotal();
            case DATAPOINTS -> limits.maxDatapointsTotal();
            case TEXT_DATAPOINTS -> limits.maxTextDatapointsTotal();
            // Resources are counted live against max_resources, where a delete frees room.
            case NODES, EDGES, BYTES -> 0;
        };
    }
}
