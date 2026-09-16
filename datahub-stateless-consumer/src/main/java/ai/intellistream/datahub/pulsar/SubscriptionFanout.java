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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    private final Map<String, Producer<DataWrapperMessage>> fanoutProducers = new ConcurrentHashMap<>();
    private final Map<String, Long> fanoutProducerNextAttemptMs = new ConcurrentHashMap<>();
    private static final long FANOUT_PRODUCER_RETRY_BACKOFF_MS = 10_000;

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
        Producer<DataWrapperMessage> producer = getOrCreateFanoutProducer(tenantId);
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

                producer.newMessage()
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
                        });
            }
        }
    }

    /**
     * Lazily build the fanout producer for a tenant on first use. Each tenant's fanout topic is
     * provisioned by {@code datahub-api}'s subscription-topic provisioner, so the consumer can
     * start before a tenant's topic exists. We retry per tenant at a throttled cadence so a
     * missing topic at boot self-heals once the API comes up, without spamming the broker on
     * every batch.
     */
    private Producer<DataWrapperMessage> getOrCreateFanoutProducer(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return null;
        Producer<DataWrapperMessage> cached = fanoutProducers.get(tenantId);
        if (cached != null) return cached;
        Long nextAttempt = fanoutProducerNextAttemptMs.get(tenantId);
        if (nextAttempt != null && System.currentTimeMillis() < nextAttempt) return null;
        synchronized (fanoutProducers) {
            cached = fanoutProducers.get(tenantId);
            if (cached != null) return cached;
            Long next = fanoutProducerNextAttemptMs.get(tenantId);
            if (next != null && System.currentTimeMillis() < next) return null;
            try {
                String topic = topicNames.getSubscriptionFanoutTopicName(tenantId);
                Producer<DataWrapperMessage> producer = pulsarClient
                        .newProducer(Schema.AVRO(DataWrapperMessage.class))
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
                        .create();
                fanoutProducers.put(tenantId, producer);
                fanoutProducerNextAttemptMs.remove(tenantId);
                log.info("Fanout producer ready for tenant {} on {}", tenantId, topic);
                return producer;
            } catch (PulsarClientException e) {
                fanoutProducerNextAttemptMs.put(tenantId, System.currentTimeMillis() + FANOUT_PRODUCER_RETRY_BACKOFF_MS);
                log.warn("Fanout producer not yet available for tenant {} ({}); retrying in {}ms",
                        tenantId, e.getMessage(), FANOUT_PRODUCER_RETRY_BACKOFF_MS);
                return null;
            } catch (IllegalStateException e) {
                // Tenant has no pulsar.tenant configured. Surface loudly but do NOT break datapoint
                // ingestion (the ClickHouse write already happened); back off so we don't log every batch.
                fanoutProducerNextAttemptMs.put(tenantId, System.currentTimeMillis() + FANOUT_PRODUCER_RETRY_BACKOFF_MS);
                log.error("Cannot fan out subscriptions for tenant {}: {}", tenantId, e.getMessage());
                return null;
            }
        }
    }

    /**
     * Flush pending sends and drop the producers. The listeners depend on this bean, so Spring
     * destroys them first: their drained inserts have issued every send before this runs.
     */
    @PreDestroy
    public void close() {
        for (Map.Entry<String, Producer<DataWrapperMessage>> entry : fanoutProducers.entrySet()) {
            try {
                entry.getValue().flush();
                entry.getValue().closeAsync();
            } catch (PulsarClientException e) {
                log.warn("Failed to flush fanout producer for tenant {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }
}
