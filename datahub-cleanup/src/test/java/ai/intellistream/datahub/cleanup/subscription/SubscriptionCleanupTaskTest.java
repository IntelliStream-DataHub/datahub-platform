// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.subscription;

import ai.intellistream.datahub.cleanup.subscription.SubscriptionCleanupTask.SubscriptionActivity;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import org.apache.pulsar.common.policies.data.PartitionedTopicStats;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Covers {@link SubscriptionCleanupTask#isOrphan}: staleness must be judged by real Pulsar
 * consume/ack activity, not the JPA {@code @UpdateTimestamp} row column (which consuming never
 * touches) — so an actively-consumed subscription whose client is briefly offline is never deleted.
 *
 * <p>Also covers {@link SubscriptionCleanupTask#mergePartitionedStats}: the fan-out topic is
 * partitioned, and Pulsar's aggregate view never carries the activity timestamps (they read 0
 * there), so the merge must take them as the max over the per-partition stats — otherwise the
 * staleness gate silently degrades to the row-age fallback in production.
 */
class SubscriptionCleanupTaskTest {

    private static final long DAY = 86_400_000L;

    private final SubscriptionCleanupProperties props = new SubscriptionCleanupProperties(); // staleAge=7d, minBacklog=1000
    private final SubscriptionCleanupTask task = new SubscriptionCleanupTask(null, null, null, null, props);
    // Fixed reference "now": tests build timestamps relative to it, independent of wall-clock drift.
    private final long nowMillis = 1_800_000_000_000L;
    private final OffsetDateTime now = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
    private final OffsetDateTime cutoff = now.minus(props.getStaleAge());

    private static SubscriptionEntity sub(boolean systemManaged, OffsetDateTime lastUpdated) {
        SubscriptionEntity s = new SubscriptionEntity();
        s.setExternalId("sub-1");
        s.setSystemManaged(systemManaged);
        s.setLastUpdated(lastUpdated);
        return s;
    }

    private static SubscriptionActivity stats(int consumers, long backlog, long lastActivity) {
        return new SubscriptionActivity(backlog, consumers, lastActivity);
    }

    @Test
    void activelyConsumedButClientOffline_isNotOrphan() {
        // Old row, no connected consumer, big backlog — but it was consumed yesterday. Must be spared.
        SubscriptionEntity s = sub(false, now.minusDays(30));
        assertFalse(task.isOrphan(s, stats(0, 5000, nowMillis - DAY), cutoff));
    }

    @Test
    void longAbandoned_isOrphan() {
        SubscriptionEntity s = sub(false, now.minusDays(30));
        assertTrue(task.isOrphan(s, stats(0, 5000, nowMillis - 30 * DAY), cutoff));
    }

    @Test
    void brandNewNeverConsumed_isNotOrphan() {
        // Never consumed (timestamp 0) but the row is minutes old — give the client time to connect.
        SubscriptionEntity s = sub(false, now.minusMinutes(1));
        assertFalse(task.isOrphan(s, stats(0, 5000, 0), cutoff));
    }

    @Test
    void neverConsumedButOldRow_isOrphan() {
        // Created long ago, never once consumed, backlog piling up — a genuine orphan.
        SubscriptionEntity s = sub(false, now.minusDays(30));
        assertTrue(task.isOrphan(s, stats(0, 5000, 0), cutoff));
    }

    @Test
    void connectedConsumer_isNotOrphan() {
        SubscriptionEntity s = sub(false, now.minusDays(30));
        assertFalse(task.isOrphan(s, stats(1, 5000, nowMillis - 30 * DAY), cutoff));
    }

    @Test
    void backlogBelowThreshold_isNotOrphan() {
        SubscriptionEntity s = sub(false, now.minusDays(30));
        assertFalse(task.isOrphan(s, stats(0, 10, nowMillis - 30 * DAY), cutoff));
    }

    @Test
    void systemManaged_isNotOrphan() {
        SubscriptionEntity s = sub(true, now.minusDays(30));
        assertFalse(task.isOrphan(s, stats(0, 5000, nowMillis - 30 * DAY), cutoff));
    }

    @Test
    void noPulsarStats_isNotOrphan() {
        SubscriptionEntity s = sub(false, now.minusDays(30));
        assertFalse(task.isOrphan(s, null, cutoff));
    }

    // ---- cross-partition merge ----

    @SuppressWarnings("unchecked")
    private static SubscriptionStats pulsarStats(long backlog, long lastConsumed, long lastAcked) {
        SubscriptionStats s = mock(SubscriptionStats.class);
        lenient().doReturn(backlog).when(s).getMsgBacklog();
        lenient().doReturn(List.of()).when(s).getConsumers();
        lenient().doReturn(lastConsumed).when(s).getLastConsumedTimestamp();
        lenient().doReturn(lastAcked).when(s).getLastAckedTimestamp();
        return s;
    }

    private static TopicStats partitionWith(Map<String, SubscriptionStats> subs) {
        TopicStats p = mock(TopicStats.class);
        lenient().doReturn(subs).when(p).getSubscriptions();
        return p;
    }

    @Test
    void merge_takesActivityFromPartitions_notTheAggregate() {
        // The aggregate reports the summed backlog but ALWAYS zero activity timestamps; the real
        // activity lives on individual partitions. The merge must surface max(activity) and keep
        // the aggregate backlog.
        SubscriptionStats aggregate = pulsarStats(5000, 0, 0);
        SubscriptionStats partition0 = pulsarStats(2000, 0, 0);                       // idle partition
        SubscriptionStats partition1 = pulsarStats(3000, nowMillis - DAY, nowMillis - 2 * DAY); // active

        PartitionedTopicStats stats = mock(PartitionedTopicStats.class);
        doReturn(Map.of("sub-1", aggregate)).when(stats).getSubscriptions();
        doReturn(Map.of(
                "topic-partition-0", partitionWith(Map.of("sub-1", partition0)),
                "topic-partition-1", partitionWith(Map.of("sub-1", partition1))
        )).when(stats).getPartitions();

        Map<String, SubscriptionActivity> merged = SubscriptionCleanupTask.mergePartitionedStats(stats);

        SubscriptionActivity activity = merged.get("sub-1");
        assertEquals(5000, activity.msgBacklog(), "backlog comes from the aggregate (summed)");
        assertEquals(nowMillis - DAY, activity.lastActivityMillis(),
                "activity must be the max over partitions — the aggregate always reads 0");
    }

    @Test
    void merge_neverConsumedAnywhere_reportsZeroActivity() {
        SubscriptionStats aggregate = pulsarStats(5000, 0, 0);
        PartitionedTopicStats stats = mock(PartitionedTopicStats.class);
        doReturn(Map.of("sub-1", aggregate)).when(stats).getSubscriptions();
        doReturn(Map.of("topic-partition-0", partitionWith(Map.of("sub-1", pulsarStats(5000, 0, 0)))))
                .when(stats).getPartitions();

        Map<String, SubscriptionActivity> merged = SubscriptionCleanupTask.mergePartitionedStats(stats);

        assertEquals(0, merged.get("sub-1").lastActivityMillis());
    }
}
