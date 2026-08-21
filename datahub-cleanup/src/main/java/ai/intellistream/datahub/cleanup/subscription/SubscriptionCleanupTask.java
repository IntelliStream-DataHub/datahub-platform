// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.policies.data.PartitionedTopicStats;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodically deletes ORPHANED user-managed subscriptions: durable Pulsar subscriptions (and their
 * {@code SubscriptionEntity} rows) that no client reconnects to, whose backlog would otherwise grow
 * until it trips the fan-out namespace's backlog quota ({@code producer_exception}) and stalls the
 * tenant's live datapoint feed for <em>every</em> subscription on that topic.
 *
 * <p>A subscription is a candidate only when ALL hold:
 * <ul>
 *   <li>it is NOT system-managed (system-managed subs are not user-owned);</li>
 *   <li>its Pulsar subscription has zero connected consumers;</li>
 *   <li>its backlog is at least {@code min-backlog-messages}; and</li>
 *   <li>it has had no Pulsar consume/ack activity within {@code stale-age} (falling back to the
 *       row's age only for a subscription that has never once been consumed) — so an actively
 *       consumed subscription whose client is briefly offline, and a brand-new not-yet-connected
 *       subscription, are both spared.</li>
 * </ul>
 *
 * <p>Defaults to dry-run: it logs what it <em>would</em> delete until an operator flips
 * {@code datahub.cleanup.subscription.dry-run} off.
 *
 * <p>The routing cache in datahub-stateless-consumer is not explicitly evicted here. Once the Pulsar
 * subscription is gone, fan-out messages still keyed for it match no subscription and are dropped by
 * the broker entry filter (no backlog accrues), and the cache entry clears on the consumer's next
 * refresh/restart. Publishing a DELETE notify to evict it eagerly is a possible follow-up.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionCleanupTask {

    private final TenantConfigService tenantConfigService;
    private final SubscriptionRepository subscriptionRepository;
    private final PulsarAdmin pulsarAdmin;
    private final TopicNames topicNames;
    private final SubscriptionCleanupProperties props;

    @Scheduled(cron = "${datahub.cleanup.subscription.cron:0 0 */6 * * *}")
    public void sweep() {
        if (!props.isEnabled()) {
            log.debug("Subscription cleanup disabled; skipping.");
            return;
        }
        Map<String, Tenant> tenants = tenantConfigService.cachedTenants;
        log.info("Subscription cleanup starting (dryRun={}, staleAge={}, minBacklog={}) over {} tenant(s).",
                props.isDryRun(), props.getStaleAge(), props.getMinBacklogMessages(), tenants.size());

        int total = 0;
        for (Tenant tenant : tenants.values()) {
            String tenantId = tenant.getOrganizationId();
            if (tenantId == null || tenantId.isBlank()) continue;
            // Route the repository to this tenant's Postgres for the duration of the sweep.
            String previous = TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(tenantId);
                total += sweepTenant(tenantId);
            } catch (Exception e) {
                log.error("Subscription cleanup failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                if (previous == null) TenantContext.clear();
                else TenantContext.setTenantId(previous);
            }
        }
        log.info("Subscription cleanup finished. {} subscription(s) {}.",
                total, props.isDryRun() ? "would be deleted (dry-run)" : "deleted");
    }

    private int sweepTenant(String tenantId) {
        String fanoutTopic;
        try {
            fanoutTopic = topicNames.getSubscriptionFanoutTopicName(tenantId);
        } catch (IllegalStateException noPulsarTenant) {
            log.debug("Tenant {} has no pulsar.tenant configured; skipping subscription cleanup.", tenantId);
            return 0;
        }

        Map<String, SubscriptionActivity> statsByName = loadSubscriptionStats(fanoutTopic);
        if (statsByName == null) return 0; // couldn't read stats this round — leave everything alone

        List<SubscriptionEntity> candidates = subscriptionRepository.findAllBySystemManagedFalse(Pageable.unpaged());
        if (candidates.isEmpty()) return 0;

        OffsetDateTime cutoff = OffsetDateTime.now().minus(props.getStaleAge());
        int count = 0;
        for (SubscriptionEntity sub : candidates) {
            SubscriptionActivity stats = statsByName.get(sub.getExternalId());
            if (!isOrphan(sub, stats, cutoff)) continue;

            long backlog = stats.msgBacklog();
            long lastActivity = stats.lastActivityMillis();
            if (props.isDryRun()) {
                log.info("[dry-run] Would delete orphaned subscription tenant={} externalId={} backlog={} lastActivity={}",
                        tenantId, sub.getExternalId(), backlog, lastActivityText(lastActivity));
                count++;
            } else if (deleteOrphan(tenantId, fanoutTopic, sub, backlog, lastActivity)) {
                count++;
            }
        }
        return count;
    }

    // Package-private for direct unit testing of the orphan decision.
    boolean isOrphan(SubscriptionEntity sub, SubscriptionActivity stats, OffsetDateTime cutoff) {
        // System-managed subs are excluded by the query, but never trust a single guard.
        if (sub.isSystemManaged()) return false;
        // No live Pulsar subscription — nothing is accumulating, so leave the (possibly dormant) row.
        if (stats == null) return false;
        if (stats.connectedConsumers() > 0) return false;
        if (stats.msgBacklog() < props.getMinBacklogMessages()) return false;

        // Judge staleness by real Pulsar activity, NOT SubscriptionEntity.lastUpdated: that column is
        // @UpdateTimestamp and consuming never writes the row, so an actively-consumed subscription
        // whose client is merely offline for a while would otherwise look stale and get deleted.
        long lastActivity = stats.lastActivityMillis();
        if (lastActivity > 0) {
            return Instant.ofEpochMilli(lastActivity).isBefore(cutoff.toInstant());
        }
        // Never consumed or acked: fall back to the row's age, so a brand-new not-yet-connected
        // subscription is spared while one created long ago and never once used still qualifies.
        // Caveat: the activity timestamps are broker-in-memory and read 0 again after a broker
        // restart/topic unload until the next consume, so this fallback can misjudge right after a
        // restart — one more reason the sweep stays dry-run by default.
        return sub.getLastUpdated() != null && sub.getLastUpdated().isBefore(cutoff);
    }

    private boolean deleteOrphan(String tenantId, String fanoutTopic, SubscriptionEntity sub, long backlog, long lastActivity) {
        String externalId = sub.getExternalId();
        try {
            // force=true: there are no consumers, but force guards against one racing in mid-delete.
            pulsarAdmin.topics().deleteSubscription(fanoutTopic, externalId, true);
        } catch (PulsarAdminException.NotFoundException alreadyGone) {
            // Pulsar side already gone — fall through and remove the DB row.
        } catch (PulsarAdminException e) {
            log.error("Failed to delete Pulsar subscription {} for tenant {}: {}", externalId, tenantId, e.getMessage());
            return false;
        }
        try {
            subscriptionRepository.delete(sub);
        } catch (Exception e) {
            log.error("Deleted Pulsar subscription {} but failed to remove its DB row (tenant {}): {}",
                    externalId, tenantId, e.getMessage());
            return false;
        }
        log.info("Deleted orphaned subscription tenant={} externalId={} backlog={} lastActivity={}",
                tenantId, externalId, backlog, lastActivityText(lastActivity));
        return true;
    }

    /** Human-readable Pulsar last consume/ack time, or {@code "never"} when the subscription has neither. */
    private static String lastActivityText(long epochMillis) {
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis).toString() : "never";
    }

    /**
     * Per-subscription view of the stats the orphan decision needs, merged across partitions:
     * backlog and connected consumers from the aggregate, and the last consume/ack activity taken as
     * the max over the per-partition stats — Pulsar's aggregate view NEVER carries the activity
     * timestamps ({@code SubscriptionStatsImpl.add()} does not merge them, they always read 0 there),
     * so reading them from the aggregate silently degrades the staleness gate to the row-age fallback.
     */
    record SubscriptionActivity(long msgBacklog, int connectedConsumers, long lastActivityMillis) {}

    /**
     * Read per-subscription stats for the fan-out topic. The fan-out topic is partitioned (8-way,
     * see SubscriptionTopicProvisioner), so request the partitioned stats WITH per-partition detail:
     * backlog/consumers come from the aggregate, the activity timestamps only exist per partition.
     * Falls back to plain stats for a non-partitioned topic.
     */
    private Map<String, SubscriptionActivity> loadSubscriptionStats(String fanoutTopic) {
        try {
            PartitionedTopicStats stats = pulsarAdmin.topics().getPartitionedStats(fanoutTopic, true);
            return mergePartitionedStats(stats);
        } catch (PulsarAdminException.NotFoundException notPartitioned) {
            try {
                Map<String, SubscriptionActivity> result = new HashMap<>();
                pulsarAdmin.topics().getStats(fanoutTopic).getSubscriptions()
                        .forEach((name, s) -> result.put(name, toActivity(s, activityOf(s))));
                return result;
            } catch (PulsarAdminException e) {
                log.warn("Failed to read stats for {}: {}", fanoutTopic, e.getMessage());
                return null;
            }
        } catch (PulsarAdminException e) {
            log.warn("Failed to read partitioned stats for {}: {}", fanoutTopic, e.getMessage());
            return null;
        }
    }

    // Package-private for direct unit testing of the cross-partition merge.
    static Map<String, SubscriptionActivity> mergePartitionedStats(PartitionedTopicStats stats) {
        Map<String, SubscriptionActivity> merged = new HashMap<>();
        // Aggregate view: backlog is summed and consumer lists are combined across partitions.
        stats.getSubscriptions().forEach((name, s) -> merged.put(name, toActivity(s, 0L)));
        // Partition views: the only place the consume/ack timestamps are populated — take the max.
        for (TopicStats partition : stats.getPartitions().values()) {
            partition.getSubscriptions().forEach((name, s) -> {
                SubscriptionActivity current = merged.get(name);
                if (current == null) return;
                long activity = activityOf(s);
                if (activity > current.lastActivityMillis()) {
                    merged.put(name, new SubscriptionActivity(
                            current.msgBacklog(), current.connectedConsumers(), activity));
                }
            });
        }
        return merged;
    }

    private static SubscriptionActivity toActivity(SubscriptionStats s, long lastActivityMillis) {
        int consumers = s.getConsumers() == null ? 0 : s.getConsumers().size();
        return new SubscriptionActivity(s.getMsgBacklog(), consumers, lastActivityMillis);
    }

    private static long activityOf(SubscriptionStats s) {
        return Math.max(s.getLastConsumedTimestamp(), s.getLastAckedTimestamp());
    }
}
