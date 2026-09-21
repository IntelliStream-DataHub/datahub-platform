// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.BatcherBuilder;
import org.apache.pulsar.client.api.HashingScheme;
import org.apache.pulsar.client.api.MessageRoutingMode;
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
 */
@org.springframework.stereotype.Component
@Slf4j
public class SubscriptionFanout {

    private final PulsarClient pulsarClient;
    private final TopicNames topicNames;
    private final SubscriptionCache subscriptionCache;
    private final AppInstanceId appInstanceId;

    // One fanout producer PER TENANT: each customer's subscription fan-out topic lives in its own
    // Pulsar tenant (resolved by TopicNames from the tenant's pulsar config). Producers are created
    // lazily on first fanout attempt for a tenant so the consumer can come up before the API has
    // provisioned that tenant's fanout topic, and self-heal once it exists.
    private final Map<String, CompletableFuture<Producer<DataWrapperMessage>>> fanoutProducers =
            new ConcurrentHashMap<>();
    private final Map<String, Long> fanoutProducerNextAttemptMs = new ConcurrentHashMap<>();
    // Not a constant so a test can shorten it; production never reassigns it.
    volatile long retryBackoffMs = 10_000;
    // Batches queued behind a producer that has not arrived yet, and the cap past which they are
    // dropped instead of retained. Bounded because a producer the broker never answers stays
    // pending for the client's whole operation timeout.
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
        CompletableFuture<Producer<DataWrapperMessage>> producer = getOrCreateFanoutProducer(tenantId);
        if (producer == null) return;

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
                send(producer, tenantId, externalId, forwarded);
            }
        }
    }

    /**
     * Publish once the producer exists. Already-built is the steady state and runs inline; while a
     * producer is still building this queues behind it rather than dropping the batch, so the first
     * datapoints written after a consumer start still reach their subscribers.
     *
     * <p>Queuing is capped: a producer the broker never answers stays pending for the client's
     * whole operation timeout, and retaining every batch published in that window would trade a
     * stalled fan-out for a heap problem. Past the cap this drops, which is what best-effort means.
     */
    private void send(CompletableFuture<Producer<DataWrapperMessage>> producer,
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
                        // `key` drives partition routing + the broker entry filter on the sub.
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
                .exceptionally(ex -> null); // the producer never arrived; already logged there
    }

    /**
     * Return the tenant's fanout producer if it is ready, otherwise start building it in the
     * background and return {@code null} for this batch. Each tenant's fanout topic is provisioned
     * by {@code datahub-api}'s subscription-topic provisioner, so the consumer can start before a
     * tenant's topic exists. We retry per tenant at a throttled cadence so a missing topic at boot
     * self-heals once the API comes up, without spamming the broker on every batch.
     *
     * <p><b>Never blocks the caller.</b> This runs on the datapoints-listener pool, after the
     * ClickHouse insert and before the ack, and building a producer is a broker round-trip that can
     * take up to the client's whole operation timeout (30s): a slow topic lookup, a bundle moving
     * between brokers, a broker restart. Creating it synchronously under one {@code fanoutProducers}
     * monitor shared by every tenant meant one tenant's slow build held that lock while every other
     * tenant's batches queued behind it, so a single stuck fan-out stalled ingest and acks
     * tenant-wide. Fan-out is best-effort; waiting for it is not.
     */
    private CompletableFuture<Producer<DataWrapperMessage>> getOrCreateFanoutProducer(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return null;
        CompletableFuture<Producer<DataWrapperMessage>> cached = fanoutProducers.get(tenantId);
        if (cached != null) return cached;
        Long nextAttempt = fanoutProducerNextAttemptMs.get(tenantId);
        if (nextAttempt != null && System.currentTimeMillis() < nextAttempt) return null;

        String topic;
        try {
            topic = topicNames.getSubscriptionFanoutTopicName(tenantId);
        } catch (IllegalStateException e) {
            // Tenant has no pulsar.tenant configured. Surface loudly but do NOT break datapoint
            // ingestion (the ClickHouse write already happened); back off so we don't log every batch.
            fanoutProducerNextAttemptMs.put(tenantId, System.currentTimeMillis() + retryBackoffMs);
            log.error("Cannot fan out subscriptions for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }

        // One build in flight per tenant: concurrent batches share the same pending future and queue
        // their sends behind it instead of each starting a create. Reserved with putIfAbsent, never
        // computeIfAbsent — the completion callback clears the slot on failure, and a failure that
        // arrives inline would then be modifying the map from inside its own mapping function, which
        // ConcurrentHashMap does not apply: the failed future would stay cached forever and the
        // tenant would never fan out again until the consumer restarted.
        CompletableFuture<Producer<DataWrapperMessage>> slot = new CompletableFuture<>();
        CompletableFuture<Producer<DataWrapperMessage>> existing = fanoutProducers.putIfAbsent(tenantId, slot);
        if (existing != null) return existing;
        buildFanoutProducer(tenantId, topic, slot);
        return slot;
    }

    private void buildFanoutProducer(String tenantId, String topic,
                                     CompletableFuture<Producer<DataWrapperMessage>> slot) {
        pulsarClient.newProducer(Schema.AVRO(DataWrapperMessage.class))
                .topic(topic)
                .producerName("subscription-fanout-" + tenantId + "-" + appInstanceId.get())
                .hashingScheme(HashingScheme.JavaStringHash)
                .messageRoutingMode(MessageRoutingMode.SinglePartition)
                // KEY_BASED batching groups by orderingKey when set, else by partition
                // key. The broker-side entry filter sees only the batch-level partition
                // key, so its accept/reject is correct for every message in a batch ONLY
                // if batches are single-partition-key — which forward() guarantees by
                // setting orderingKey equal to the partition key.
                .batcherBuilder(BatcherBuilder.KEY_BASED)
                .sendTimeout(10, TimeUnit.SECONDS)
                .createAsync()
                .whenComplete((producer, err) -> {
                    if (err != null) {
                        // Clear the slot so the next batch past the backoff retries.
                        fanoutProducers.remove(tenantId, slot);
                        fanoutProducerNextAttemptMs.put(tenantId,
                                System.currentTimeMillis() + retryBackoffMs);
                        log.warn("Fanout producer not yet available for tenant {} ({}); retrying in {}ms",
                                tenantId, err.getMessage(), retryBackoffMs);
                        slot.completeExceptionally(err);
                        return;
                    }
                    if (closed) {
                        // Shutdown raced the build; don't leak the connection.
                        producer.closeAsync();
                        fanoutProducers.remove(tenantId, slot);
                        slot.completeExceptionally(new IllegalStateException("fan-out is shutting down"));
                        return;
                    }
                    fanoutProducerNextAttemptMs.remove(tenantId);
                    log.info("Fanout producer ready for tenant {} on {}", tenantId, topic);
                    slot.complete(producer);
                });
    }

    /**
     * Flush pending sends and drop the producers. The listeners depend on this bean, so Spring
     * destroys them first: their drained inserts have issued every send before this runs.
     */
    @PreDestroy
    public void close() {
        closed = true;
        for (Map.Entry<String, CompletableFuture<Producer<DataWrapperMessage>>> entry : fanoutProducers.entrySet()) {
            Producer<DataWrapperMessage> producer = entry.getValue().getNow(null);
            if (producer == null) continue;  // still building; the build closes it on completion
            try {
                producer.flush();
                producer.closeAsync();
            } catch (PulsarClientException e) {
                log.warn("Failed to flush fanout producer for tenant {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }
}
