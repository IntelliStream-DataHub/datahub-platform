// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of subscription external ids keyed by tenant and timeseries id.
 * Populated at startup by {@code SubscriptionNotifyListener} (via {@link #loadAll()}, after it
 * subscribes to the notify topic) and kept current by that listener as subscriptions are created
 * and deleted at runtime.
 * <p>
 * Shape: {@code tenantId -> (timeseriesId -> set of subscription externalIds)}.
 */
@Component
@Slf4j
@DependsOn({"tenantConfigService"})
public class SubscriptionCache {

    private final Map<String, Map<Long, Set<String>>> cache = new ConcurrentHashMap<>();

    private final SubscriptionRepository subscriptionRepository;
    private final TenantConfigService tenantConfigService;

    public SubscriptionCache(SubscriptionRepository subscriptionRepository,
                             TenantConfigService tenantConfigService) {
        this.subscriptionRepository = subscriptionRepository;
        this.tenantConfigService = tenantConfigService;
    }

    /**
     * Populate the cache by walking every tenant known to {@link TenantConfigService} and loading its
     * subscriptions from Postgres. Called by {@code SubscriptionNotifyListener} <em>after</em> it has
     * subscribed to the notify topic, so the subscription's stream position is pinned before this
     * snapshot is read — a subscription created between the two is caught by the stream rather than
     * being missed by both.
     */
    public void loadAll() {
        int bindings = 0;
        for (Tenant tenant : tenantConfigService.cachedTenants.values()) {
            bindings += loadForTenant(tenant.getOrganizationId());
        }
        log.info("SubscriptionCache loaded {} timeseries-binding(s) across {} tenant(s).",
                bindings, tenantConfigService.cachedTenants.size());
    }

    /**
     * Load all subscriptions for a single tenant into the cache. Sets
     * {@link TenantContext} around the repository call so
     * {@code StatelessRoutingDataSource} routes the query to the tenant's Postgres,
     * then restores the previous context.
     *
     * @return number of subscription rows read for this tenant (0 on failure)
     */
    private int loadForTenant(String tenantId) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            List<SubscriptionEntity> subs = subscriptionRepository.findAll();
            int bindings = 0;
            for (SubscriptionEntity sub : subs) {
                if (sub.getTimeseries() == null) continue;
                for (var ts : sub.getTimeseries()) {
                    add(tenantId, ts.getId(), sub.getExternalId());
                    bindings++;
                }
            }
            return bindings;
        } catch (Exception e) {
            log.error("Failed to load subscriptions for tenant {}: {}", tenantId, e.getMessage(), e);
            return 0;
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    /**
     * Record a subscription binding. Creates the tenant bucket and the timeseries
     * set on demand. Safe to call concurrently with {@link #remove}: the set mutation
     * runs inside {@code compute()} on both map levels, so it holds the same tenant-bin
     * lock as a concurrent {@code remove()} pruning an empty bucket — a binding can't be
     * lost to a racing prune. Duplicate adds are idempotent. Called from the startup load
     * and from {@code SubscriptionNotifyListener} when a new subscription is created at
     * runtime.
     */
    public void add(String tenantId, Long timeseriesId, String subscriptionExternalId) {
        cache.compute(tenantId, (t, byTs) -> {
            if (byTs == null) byTs = new ConcurrentHashMap<>();
            byTs.compute(timeseriesId, (ts, subs) -> {
                if (subs == null) subs = ConcurrentHashMap.newKeySet();
                subs.add(subscriptionExternalId);
                return subs;
            });
            return byTs;
        });
    }

    /**
     * Hot-path lookup used by the datapoints listener to fan out to subscription topics.
     *
     * @return the subscription external ids bound to {@code timeseriesId} for this
     *         tenant, or an empty set if none exist — never {@code null}.
     */
    public Set<String> getSubscriptionExternalIds(String tenantId, Long timeseriesId) {
        Map<Long, Set<String>> byTs = cache.get(tenantId);
        if (byTs == null) return Set.of();
        Set<String> subs = byTs.get(timeseriesId);
        return subs == null ? Set.of() : subs;
    }

    /**
     * Remove a subscription binding. Called by {@code SubscriptionNotifyListener} when a
     * DELETE notify message arrives. Prunes the timeseries set and the tenant bucket when
     * they become empty so the cache doesn't retain stale keys. The prune runs inside
     * {@code compute()} on both levels (returning {@code null} to drop a now-empty bucket
     * under the same tenant-bin lock {@link #add} holds), so it can't race a concurrent
     * add into a bucket it is about to remove. Safe to call for bindings that were never
     * present.
     */
    public void remove(String tenantId, Long timeseriesId, String subscriptionExternalId) {
        cache.computeIfPresent(tenantId, (t, byTs) -> {
            byTs.computeIfPresent(timeseriesId, (ts, subs) -> {
                subs.remove(subscriptionExternalId);
                return subs.isEmpty() ? null : subs;
            });
            return byTs.isEmpty() ? null : byTs;
        });
    }
}
