// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Live "total X ingested" counter, shared machinery behind the home dashboard's fast-moving tallies
 * — currently one instance each for {@code "datapoints"} (see {@code LiveIngestCounterConfig}) and
 * {@code "events"}, read by {@link StatsService}. Not a Spring-scanned {@code @Component}: each
 * metric gets its own bean instance (own key prefix, own ClickHouse source-of-truth supplier, own
 * in-memory state) via explicit {@code @Bean} factory methods, since {@code @Scheduled} works
 * per-instance regardless of how the bean was registered.
 *
 * <p>Each API instance accumulates ingested counts in memory per tenant ({@link #recordIngested},
 * called inline right after a successful Pulsar publish — no I/O, so no need to be async itself) and
 * flushes the accumulated delta to that tenant's Valkey every {@link #FLUSH_MS} via {@link
 * ValkeyService#increment}. That's one round trip per active tenant per interval, however many API
 * instances are running — not one per insert request — and concurrent flushes from multiple
 * instances combine correctly since INCRBY is atomic server-side, so no cross-instance coordination
 * is needed.
 *
 * <p>The Valkey key carries no TTL: unlike {@link StatsService}'s recomputed metrics, this is a
 * running total that must survive between flushes, not a cache. It's lazily seeded once from the
 * ClickHouse source of truth the first time anything touches it — critically, that includes {@link
 * #flush()} itself, not just {@link #getCurrentTotal()}: an item ingested (and flushed) before the
 * dashboard has ever been read for a tenant must not silently INCRBY a nonexistent key and start the
 * counter at 0, permanently losing the tenant's pre-existing historical total.
 *
 * <p>The live counter can drift from ClickHouse over time — a lost flush, a restart dropping
 * in-memory deltas, etc. — so {@link #reconcile()} compares the two hourly and resets Valkey to the
 * ClickHouse count if they've drifted apart by more than {@link #MISMATCH_THRESHOLD}. Being exactly
 * accurate isn't the goal (a dashboard tile, not a billing figure); staying within that threshold is.
 * The threshold also absorbs ClickHouse's normal lag behind Valkey (an item counts here the moment
 * it's published to Pulsar, before the downstream consumer has inserted it into ClickHouse), so an
 * ordinary in-flight gap doesn't trigger a spurious correction — only genuine drift does.
 *
 * <p>That same drift check also runs on an instance's <em>first</em> touch of a tenant, not just on
 * the hourly schedule — {@code seededTenants} is in-memory and empty again after every restart, but
 * the Valkey key survives restarts, so without this a stale/wrong value (e.g. left over from before
 * this class existed, or drift accumulated while the instance was down) would be trusted verbatim
 * until the next hourly tick instead of being corrected right away.
 */
@Slf4j
public class LiveIngestCounter {

    private static final long FLUSH_MS = 2000;
    private static final long RECONCILE_MS = 3_600_000; // hourly
    private static final double MISMATCH_THRESHOLD = 0.05; // >5% relative difference triggers a resync

    private final String keyPrefix;      // + tenantId, no TTL
    private final String metricName;     // for logging only
    private final ValkeyService valkeyService;
    private final LongSupplier trueCount; // ClickHouse source of truth for this metric

    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();
    // Tenants this instance has confirmed have a seeded key. Once true, both callers below skip
    // straight to a plain read/increment — a key that exists never needs re-seeding.
    private final Set<String> seededTenants = ConcurrentHashMap.newKeySet();

    public LiveIngestCounter(String keyPrefix, String metricName, ValkeyService valkeyService, LongSupplier trueCount) {
        this.keyPrefix = keyPrefix;
        this.metricName = metricName;
        this.valkeyService = valkeyService;
        this.trueCount = trueCount;
    }

    /** In-memory only — cheap enough to call directly on the request thread. */
    public void recordIngested(String tenantId, long count) {
        if (tenantId == null || count <= 0) return;
        pending.computeIfAbsent(tenantId, k -> new AtomicLong()).addAndGet(count);
    }

    /** Current live total for the current tenant (see {@link TenantContext}), seeding once if never initialized. */
    public long getCurrentTotal() {
        return ensureSeededAndGet(TenantContext.getTenantId());
    }

    @Scheduled(fixedRate = FLUSH_MS)
    void flush() {
        for (Map.Entry<String, AtomicLong> e : pending.entrySet()) {
            long delta = e.getValue().getAndSet(0);
            if (delta <= 0) continue;
            String tenantId = e.getKey();
            try {
                TenantContext.runWith(tenantId, () -> {
                    ensureSeededAndGet(tenantId); // make sure a baseline exists before adding to it
                    valkeyService.increment(keyPrefix + tenantId, delta);
                });
            } catch (Exception ex) {
                log.warn("{} ingest counter: flush failed for tenant {}, will retry next interval: {}",
                        metricName, tenantId, ex.getMessage());
                // Don't lose the delta on a transient Valkey failure — fold it back in for the next flush.
                pending.computeIfAbsent(tenantId, k -> new AtomicLong()).addAndGet(delta);
            }
        }
    }

    /** Hourly drift check against ClickHouse, per tenant this instance is actively tracking. */
    @Scheduled(fixedRate = RECONCILE_MS)
    void reconcile() {
        for (String tenantId : seededTenants) {
            try {
                TenantContext.runWith(tenantId, () ->
                        seedOrReconcile(tenantId, valkeyService.getString(keyPrefix + tenantId)));
            } catch (Exception ex) {
                log.warn("{} ingest counter: reconcile failed for tenant {}: {}", metricName, tenantId, ex.getMessage());
            }
        }
    }

    /**
     * Ensure {@code tenantId}'s counter key exists and is trustworthy, seeding it from ClickHouse if
     * this is the first time either {@link #getCurrentTotal} or {@link #flush} has touched it on
     * this instance, and return its current value either way.
     */
    private long ensureSeededAndGet(String tenantId) {
        if (tenantId == null) return 0L;
        String key = keyPrefix + tenantId;
        if (!seededTenants.add(tenantId)) {
            return parse(valkeyService.getString(key));
        }
        return seedOrReconcile(tenantId, valkeyService.getString(key));
    }

    /**
     * Seed {@code tenantId}'s key from ClickHouse if {@code currentRaw} (its current Valkey value,
     * null if absent) is missing, or resync it to ClickHouse if it's present but has drifted beyond
     * {@link #MISMATCH_THRESHOLD} — otherwise leave it alone and return it as-is. Seeding uses {@link
     * ValkeyService#setIfAbsent} so a losing racer (another caller or instance seeding the same key
     * concurrently) doesn't clobber the winner's value; it just re-reads whichever value actually won.
     */
    private long seedOrReconcile(String tenantId, String currentRaw) {
        String key = keyPrefix + tenantId;
        if (currentRaw == null) {
            long seed = trueCount.getAsLong();
            boolean won = valkeyService.setIfAbsent(key, String.valueOf(seed));
            return won ? seed : parse(valkeyService.getString(key));
        }
        long vkCount = parse(currentRaw);
        long chCount = trueCount.getAsLong();
        long diff = Math.abs(chCount - vkCount);
        double relativeDiff = chCount == 0 ? (vkCount == 0 ? 0 : 1) : (double) diff / chCount;
        if (relativeDiff > MISMATCH_THRESHOLD) {
            log.info("{} ingest counter: tenant {} drifted (valkey={}, clickhouse={}, {}%) — resyncing to ClickHouse",
                    metricName, tenantId, vkCount, chCount, Math.round(relativeDiff * 100));
            valkeyService.put(Map.of(key, String.valueOf(chCount)));
            return chCount;
        }
        return vkCount;
    }

    private static long parse(String raw) {
        try {
            return raw != null ? Long.parseLong(raw) : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
