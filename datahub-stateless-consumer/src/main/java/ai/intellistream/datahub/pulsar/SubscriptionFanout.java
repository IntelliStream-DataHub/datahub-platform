// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.BatcherBuilder;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fan-out of inserted datapoints to the WebSocket subscriptions, shared by the Avro and the
 * binary listener. For every subscription bound to a timeseries in a batch, publishes the matching
 * {@link DataCollectionString} to the tenant's fan-out topic keyed by the subscription externalId;
 * the broker-side entry filter then dispatches each message only to the Pulsar subscription whose
 * {@code filter.key} property matches. Subscriptions come from the in-memory
 * {@link SubscriptionCache}, so there is no database call on the hot path. Best-effort: failures
 * are logged but never block the ClickHouse write or the ack.
 *
 * <h2>One producer per partition, not one per topic</h2>
 * The fan-out topic is partitioned (8 ways, see {@code SubscriptionTopicProvisioner}). Publishing
 * through a single producer on the parent topic makes the whole tenant's fan-out all-or-nothing:
 * Pulsar's partitioned producer only becomes usable once <em>every</em> partition's internal
 * producer has connected, so one unavailable partition silences every subscription in the tenant,
 * including those routed elsewhere. That is not hypothetical — the fan-out namespace's backlog
 * quota is {@code producer_exception}, so a single abandoned subscription whose backlog breaches
 * the quota makes the broker refuse producer creation on its partition, and with it the tenant's
 * entire live feed.
 *
 * <p>So this class resolves the partition list once per tenant and holds a producer per partition,
 * each built independently with its own retry budget. A blocked partition now costs only the
 * subscriptions whose externalId routes to it. Routing moves here from Pulsar's
 * {@code SinglePartition} router and keeps its formula, so a subscription keeps landing on the
 * partition it always did — the durable cursor the api created on the parent topic spans all
 * partitions either way, and the broker entry filter matches on the message's partition key, which
 * is still the externalId.
 */
@org.springframework.stereotype.Component
@Slf4j
public class SubscriptionFanout {

    private final PulsarClient pulsarClient;
    private final TopicNames topicNames;
    private final SubscriptionCache subscriptionCache;
    private final AppInstanceId appInstanceId;

    // Per tenant, the partition topic names of its fan-out topic in partition order. Each customer's
    // fan-out topic lives in its own Pulsar tenant (resolved by TopicNames from the tenant's pulsar
    // config) and is provisioned by datahub-api, so resolution is lazy and retried — the consumer
    // can come up before the api has provisioned the topic, and self-heals once it exists.
    private final Map<String, CompletableFuture<List<String>>> fanoutPartitions = new ConcurrentHashMap<>();
    // One producer per partition topic, keyed by the full partition topic name so tenants cannot
    // collide. Built lazily and independently: a partition the broker refuses does not stop the
    // others from serving their subscriptions.
    private final Map<String, CompletableFuture<Producer<DataWrapperMessage>>> partitionProducers =
            new ConcurrentHashMap<>();
    private final Map<String, Long> nextAttemptMs = new ConcurrentHashMap<>();
    // Not a constant so a test can shorten it; production never reassigns it.
    volatile long retryBackoffMs = 10_000;
    // Handed to callers while a key is backing off. One shared instance: the alternative allocates
    // a throwable and a future per message for as long as a partition stays unavailable.
    private static final CompletableFuture<Producer<DataWrapperMessage>> BACKING_OFF =
            CompletableFuture.failedFuture(new IllegalStateException("fan-out producer is backing off"));

    // Batches queued behind a producer that has not arrived yet, and the cap past which they are
    // dropped instead of retained. Bounded because a producer the broker refuses stays pending for
    // the client's whole operation timeout.
    private final AtomicInteger pendingSends = new AtomicInteger();
    private final AtomicBoolean pendingSendsExceeded = new AtomicBoolean();
    private static final int MAX_PENDING_SENDS = 1_000;

    // Set by @PreDestroy so a build that completes after shutdown closes its producer instead of
    // publishing it into a map nothing will drain.
    private volatile boolean closed = false;

    public SubscriptionFanout(PulsarClient pulsarClient, TopicNames topicNames,
                              SubscriptionCache subscriptionCache, AppInstanceId appInstanceId) {
        this.pulsarClient = pulsarClient;
        this.topicNames = topicNames;
        this.subscriptionCache = subscriptionCache;
        this.appInstanceId = appInstanceId;
    }

    /** Whether any subscription is bound to the timeseries, so a caller can skip decoding for it. */
    public boolean hasSubscribers(String tenantId, long timeseriesId) {
        return !subscriptionCache.getSubscriptionExternalIds(tenantId, timeseriesId).isEmpty();
    }

    public void forward(DataWrapperMessage batch) {
        Collection<DataCollectionString> items = batch.getItems();
        if (items == null || items.isEmpty()) return;

        String tenantId = batch.getTenantId();
        CompletableFuture<List<String>> partitions = getOrResolvePartitions(tenantId);
        if (partitions == null) return;

        for (DataCollectionString item : items) {
            if (item.getId() == null) continue;

            Set<String> externalIds = subscriptionCache.getSubscriptionExternalIds(tenantId, item.getId());
            if (externalIds.isEmpty()) continue;

            for (String externalId : externalIds) {
                DataWrapperMessage forwarded = new DataWrapperMessage(
                        batch.getEventObject(),
                        batch.getEventAction(),
                        List.of(item),
                        tenantId
                );
                // Partitions resolve once per tenant, so this is normally already complete and the
                // send runs inline; on the very first batch it queues behind the lookup.
                queue(partitions.thenCompose(names -> {
                    String partitionTopic = names.get(partitionFor(externalId, names.size()));
                    return getOrCreatePartitionProducer(partitionTopic, tenantId);
                }), tenantId, externalId, forwarded);
            }
        }
    }

    /**
     * The partition a subscription's messages belong on. This is Pulsar's own
     * {@code SinglePartitionMessageRouter} formula for a keyed message under
     * {@code HashingScheme.JavaStringHash}, kept identical so taking over routing does not move an
     * existing subscription to a partition its durable cursor's backlog and ordering do not follow.
     */
    static int partitionFor(String externalId, int partitions) {
        return (externalId.hashCode() & Integer.MAX_VALUE) % partitions;
    }

    /**
     * Publish once the producer exists. Already-built is the steady state and runs inline; while a
     * producer is still building this queues behind it rather than dropping the batch, so the first
     * datapoints written after a consumer start still reach their subscribers.
     *
     * <p>Queuing is capped: a producer the broker refuses outright stays pending for the client's
     * whole operation timeout, and retaining every batch published in that window would trade a
     * stalled fan-out for a heap problem. Past the cap this drops, which is what best-effort means.
     */
    private void queue(CompletableFuture<Producer<DataWrapperMessage>> producer,
                       String tenantId, String externalId, DataWrapperMessage forwarded) {
        if (!producer.isDone()) {
            if (pendingSends.get() >= MAX_PENDING_SENDS) {
                if (pendingSendsExceeded.compareAndSet(false, true)) {
                    log.warn("Fan-out has {} batches queued behind producers that have not arrived "
                            + "(most recently for tenant {}); dropping until they recover.",
                            MAX_PENDING_SENDS, tenantId);
                }
                return;
            }
            pendingSends.incrementAndGet();
            producer.whenComplete((p, err) -> {
                pendingSends.decrementAndGet();
                pendingSendsExceeded.set(false);
            });
        }
        producer.thenAccept(p -> p.newMessage()
                        // Still the partition key, even though this producer targets one partition
                        // directly: the broker entry filter routes on it, not on the topic.
                        .key(externalId)
                        // orderingKey MUST equal the partition key. KEY_BASED batching groups a
                        // batch by orderingKey when one is set, and the broker entry filter routes
                        // by the batch entry's partition key — so a divergent orderingKey (e.g.
                        // timeseriesId) folds messages for DIFFERENT subscriptions into one batch
                        // carrying a single partition key: the filter then delivers the whole batch
                        // to one subscription (duplicates) and drops it for the rest (silent loss).
                        // KeyShared dispatch also hashes on orderingKey, so this pins each
                        // subscription's stream to one consumer, preserving per-subscription order.
                        .orderingKey(externalId.getBytes(StandardCharsets.UTF_8))
                        .value(forwarded)
                        .sendAsync()
                        .exceptionally(ex -> {
                            log.error("Failed to forward datapoints for subscription {}: {}", externalId, ex.getMessage());
                            return null;
                        }))
                .exceptionally(ex -> null); // producer or partition lookup failed; logged there
    }

    /** The tenant's fan-out partition topic names, or {@code null} while the tenant is backing off. */
    private CompletableFuture<List<String>> getOrResolvePartitions(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return null;
        CompletableFuture<List<String>> cached = fanoutPartitions.get(tenantId);
        if (cached != null) return cached;
        if (backingOff(tenantId)) return null;

        String topic;
        try {
            topic = topicNames.getSubscriptionFanoutTopicName(tenantId);
        } catch (IllegalStateException e) {
            // Tenant has no pulsar.tenant configured. Surface loudly but do NOT break datapoint
            // ingestion (the ClickHouse write already happened); back off so we don't log every batch.
            backOff(tenantId);
            log.error("Cannot fan out subscriptions for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }

        // Reserve the slot with putIfAbsent, never computeIfAbsent: the completion callback clears
        // the slot on failure, and a failure that arrives inline would then be modifying the map
        // from inside its own mapping function — which ConcurrentHashMap does not apply, leaving
        // the failed future cached forever and the tenant permanently un-fanned-out.
        CompletableFuture<List<String>> slot = new CompletableFuture<>();
        CompletableFuture<List<String>> existing = fanoutPartitions.putIfAbsent(tenantId, slot);
        if (existing != null) return existing;
        resolvePartitions(tenantId, topic, slot);
        return slot;
    }

    private void resolvePartitions(String tenantId, String topic, CompletableFuture<List<String>> slot) {
        pulsarClient.getPartitionsForTopic(topic).whenComplete((names, err) -> {
            if (err != null || names == null || names.isEmpty()) {
                String reason = err != null ? err.getMessage() : "no partitions reported";
                fanoutPartitions.remove(tenantId, slot);
                backOff(tenantId);
                log.warn("Fanout topic {} for tenant {} not resolvable yet ({}); retrying in {}ms",
                        topic, tenantId, reason, retryBackoffMs);
                slot.completeExceptionally(err != null ? err : new IllegalStateException(reason));
                return;
            }
            nextAttemptMs.remove(tenantId);
            log.info("Fanout topic {} for tenant {} has {} partition(s).", topic, tenantId, names.size());
            slot.complete(List.copyOf(names));
        });
    }

    /**
     * The producer for one partition topic. Each partition carries its own backoff, so a partition
     * the broker refuses — over its backlog quota, say — costs only the subscriptions routed to it.
     */
    private CompletableFuture<Producer<DataWrapperMessage>> getOrCreatePartitionProducer(
            String partitionTopic, String tenantId) {
        CompletableFuture<Producer<DataWrapperMessage>> cached = partitionProducers.get(partitionTopic);
        if (cached != null) return cached;
        if (backingOff(partitionTopic)) return BACKING_OFF;

        // putIfAbsent, not computeIfAbsent — see getOrResolvePartitions.
        CompletableFuture<Producer<DataWrapperMessage>> slot = new CompletableFuture<>();
        CompletableFuture<Producer<DataWrapperMessage>> existing =
                partitionProducers.putIfAbsent(partitionTopic, slot);
        if (existing != null) return existing;
        buildProducer(partitionTopic, tenantId, slot);
        return slot;
    }

    private void buildProducer(String partitionTopic, String tenantId,
                               CompletableFuture<Producer<DataWrapperMessage>> slot) {
        pulsarClient.newProducer(Schema.AVRO(DataWrapperMessage.class))
                .topic(partitionTopic)
                .producerName("subscription-fanout-" + tenantId + "-" + appInstanceId.get())
                // KEY_BASED batching groups by orderingKey when set, else by partition key. The
                // broker-side entry filter sees only the batch-level partition key, so its
                // accept/reject is correct for every message in a batch ONLY if batches are
                // single-partition-key — which forward() guarantees by setting orderingKey equal to
                // the partition key.
                .batcherBuilder(BatcherBuilder.KEY_BASED)
                .sendTimeout(10, TimeUnit.SECONDS)
                .createAsync()
                .whenComplete((producer, err) -> {
                    if (err != null) {
                        // Clear the slot so the next batch past the backoff retries this partition.
                        partitionProducers.remove(partitionTopic, slot);
                        backOff(partitionTopic);
                        log.warn("Fanout producer not yet available for {} ({}); retrying in {}ms",
                                partitionTopic, err.getMessage(), retryBackoffMs);
                        slot.completeExceptionally(err);
                        return;
                    }
                    if (closed) {
                        // Shutdown raced the build; don't leak the connection.
                        producer.closeAsync();
                        partitionProducers.remove(partitionTopic, slot);
                        slot.completeExceptionally(new IllegalStateException("fan-out is shutting down"));
                        return;
                    }
                    nextAttemptMs.remove(partitionTopic);
                    log.info("Fanout producer ready on {}", partitionTopic);
                    slot.complete(producer);
                });
    }

    private boolean backingOff(String key) {
        Long next = nextAttemptMs.get(key);
        return next != null && System.currentTimeMillis() < next;
    }

    private void backOff(String key) {
        nextAttemptMs.put(key, System.currentTimeMillis() + retryBackoffMs);
    }

    /**
     * Flush pending sends and drop the producers. The listeners depend on this bean, so Spring
     * destroys them first: their drained inserts have issued every send before this runs.
     */
    @PreDestroy
    public void close() {
        closed = true;
        for (Map.Entry<String, CompletableFuture<Producer<DataWrapperMessage>>> entry : partitionProducers.entrySet()) {
            Producer<DataWrapperMessage> producer = entry.getValue().getNow(null);
            if (producer == null) continue;  // still building; the build closes it on completion
            try {
                producer.flush();
                producer.closeAsync();
            } catch (PulsarClientException e) {
                log.warn("Failed to flush fanout producer for {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }
}
