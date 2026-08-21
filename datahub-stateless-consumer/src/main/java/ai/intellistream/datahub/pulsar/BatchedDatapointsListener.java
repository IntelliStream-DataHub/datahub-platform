// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.responses.DataCollectionBin;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.clickhouse.DatapointBinaryConverter;
import ai.intellistream.datahub.clickhouse.ClickHouseDatapointService;
import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
// Depend on the notify listener (not the cache bean): it subscribes to the notify feed and loads the
// Postgres snapshot into the cache in its @PostConstruct, so this must start only after the cache is
// populated, or early datapoints would miss fan-out.
@DependsOn({"subscriptionNotifyListener"})
@RequiredArgsConstructor
public class BatchedDatapointsListener {

    private volatile boolean isRunning = false;
    private final PulsarClient pulsarClient;
    private Consumer<DataWrapperBin> consumer;
    private final ClickHouseDatapointService clickHouseDatapointService;
    private final TopicNames topicNames;
    private final SubscriptionCache subscriptionCache;
    private final AppInstanceId appInstanceId;
    private ExecutorService executorService;

    // One fanout producer PER TENANT — each customer's subscription fan-out topic lives in its
    // own Pulsar tenant (resolved by TopicNames from the tenant's pulsar config), so a single
    // shared producer no longer suffices. Messages are keyed by subscription externalId so
    // Pulsar's hashing scheme routes them to a stable partition and the broker-side entry filter
    // drops them for subscriptions whose filter.key property differs. Producers are created
    // lazily on first fanout attempt for a tenant so the consumer can come up before the API has
    // provisioned that tenant's fanout topic, and self-heal once it exists.
    private final Map<String, Producer<DataWrapperMessage>> fanoutProducers = new ConcurrentHashMap<>();
    private final Map<String, Long> fanoutProducerNextAttemptMs = new ConcurrentHashMap<>();
    private static final long FANOUT_PRODUCER_RETRY_BACKOFF_MS = 10_000;

    @PostConstruct
    public void init() {
        this.executorService = Executors.newFixedThreadPool(
                8,
                new DatapointsThreadFactory("datapoints-listener")
        );

        try {
            // Create a consumer with batch receive policy
            // Timeout-bound batches each become one ClickHouse insert (one part per table). 500ms
            // (up from 250ms) halves the worst-case part creation rate for low/medium-volume
            // periods, trading some live-tail freshness for less part fragmentation on the hot
            // (current-year) partition — see min_age_to_force_merge_seconds in clickhouse.sql for
            // the complementary fix on the merge side.
            var brp = BatchReceivePolicy.builder()
                    .maxNumMessages(-1)
                    .maxNumBytes(20 * 1024 * 1024)
                    .timeout(500, TimeUnit.MILLISECONDS)
                    .build();

            // Poison messages that repeatedly fail to process are routed to a dead-letter topic
            // instead of blocking the consumer in a tight redelivery loop. Pulsar auto-names the
            // DLQ topic as "<topic>-<subscription>-DLQ" when deadLetterTopic is omitted.
            var deadLetterPolicy = DeadLetterPolicy.builder()
                    .maxRedeliverCount(10)
                    .build();

            consumer = pulsarClient.newConsumer(Schema.AVRO(DataWrapperBin.class))
                    .subscriptionName(TopicNames.ALL_SUBSCRIPTIONS_NAME)
                    // See GraphEventNeo4jConsumer. On the datapoint funnel this is what loses
                    // a backfill written seconds after boot: measured on a fresh stack, one
                    // partition published 14 and delivered 13, another 7 and delivered 5, and
                    // the api answered 2xx for every one. Replay is safe — datapoints upsert
                    // on (timeseries, timestamp).
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                    .consumerName("batched-datapoints-all-ch-consumer")
                    .batchReceivePolicy(brp)
                    .topic(topicNames.getAllDatapointsTopicName())
                    .ackTimeout(120, TimeUnit.SECONDS)
                    .subscriptionType(SubscriptionType.Shared)
                    .autoUpdatePartitionsInterval(30, TimeUnit.SECONDS)
                    .deadLetterPolicy(deadLetterPolicy)
                    .subscribe();

            startConsumer();
            log.debug("Started all-datapoints consumer.");

        } catch (Exception e) {
            // Fail fast. Swallowing this leaves the headless app "up" but subscribed to nothing —
            // a brief Pulsar outage at boot would then stall datapoint ingestion permanently with no
            // health signal. Abort startup so the orchestrator restarts us. (Mirrors
            // SubscriptionNotifyListener.)
            throw new IllegalStateException("Failed to start the all-datapoints consumer; refusing to start.", e);
        }
    }

    @Async
    public void startConsumer() {
        isRunning = true;
        receiveMessages();
    }

    public void stopConsumer() {
        isRunning = false;
    }

    private void receiveMessages() {
        if (isRunning) {

            consumer.batchReceiveAsync()
                    .thenAcceptAsync(messages -> {
                        handleDatapointMessages(messages);
                    }, this.executorService)
                    .exceptionally(ex -> {
                        System.err.printf("Failed to receive messages: %s%n", ex.getMessage());
                        // Continue receiving messages after a failure
                        receiveMessages();
                        return null;
                    });
        } else {
            log.debug("Stopped all-datapoints consumer.");
        }
    }

    private void handleDatapointMessages(Messages<DataWrapperBin> messages) {
        // Group CREATE items by tenant and remember which Pulsar messages contributed, so that
        // a single ClickHouse insert failure nacks only that tenant's contributing messages
        // instead of leaving the whole batch in limbo.
        Map<String, List<DataCollectionBin>> createItemsByTenant = new HashMap<>();
        Map<String, List<Message<DataWrapperBin>>> createMessagesByTenant = new HashMap<>();
        Map<String, DataWrapperBin> fanoutTemplateByTenant = new HashMap<>();

        for (Message<DataWrapperBin> msg : messages) {
            try {
                DataWrapperBin message = msg.getValue();
                String tenantId = message.getTenantId();

                if (message.getEventObject() == EventObject.DATAPOINTS) {
                    switch (message.getEventAction()) {
                        case CREATE -> {
                            createItemsByTenant
                                    .computeIfAbsent(tenantId, k -> new ArrayList<>())
                                    .addAll(message.getItems());
                            createMessagesByTenant
                                    .computeIfAbsent(tenantId, k -> new ArrayList<>())
                                    .add(msg);
                            fanoutTemplateByTenant.putIfAbsent(tenantId, message);
                        }
                        case DELETE -> {
                            try {
                                clickHouseDatapointService.deleteBinaryDatapoints(message.getItems(), tenantId);
                                consumer.acknowledge(msg);
                            } catch (Exception e) {
                                log.error("Delete failed for tenant {}: {}", tenantId, e.getMessage(), e);
                                consumer.negativeAcknowledge(msg);
                            }
                        }
                    }
                } else {
                    // Unsupported event object on this topic — ack rather than loop forever.
                    log.warn("Skipping unsupported event object: {}", message.getEventObject());
                    consumer.acknowledge(msg);
                }
            } catch (Exception e) {
                // Malformed payload or schema mismatch. Nack so Pulsar redelivers; if it keeps
                // failing, the dead-letter policy eventually diverts it.
                log.error("Failed to process datapoints message: {}", e.getMessage(), e);
                consumer.negativeAcknowledge(msg);
            }
        }

        // Bulk-insert each tenant's CREATE items. On failure, nack the contributing messages so
        // Pulsar can redeliver them (and eventually DLQ if the failure is persistent).
        for (var entry : createItemsByTenant.entrySet()) {
            String tenantId = entry.getKey();
            List<DataCollectionBin> items = entry.getValue();
            List<Message<DataWrapperBin>> contributing = createMessagesByTenant.get(tenantId);

            if (items.isEmpty()) {
                ackAll(contributing);
                continue;
            }

            try {
                // Stream the pre-encoded value bytes straight into ClickHouse — no per-value parse.
                clickHouseDatapointService.insertBinaryDatapoints(items, tenantId);

                DataWrapperBin template = fanoutTemplateByTenant.get(tenantId);
                DataWrapperBin fanoutBatch = new DataWrapperBin();
                fanoutBatch.setEventObject(template.getEventObject());
                fanoutBatch.setEventAction(template.getEventAction());
                fanoutBatch.setItems(items);
                fanoutBatch.setTenantId(tenantId);
                // Decode back to the string shape only for the fan-out subset; subscribers unchanged.
                forwardToSubscriptionTopics(DatapointBinaryConverter.toStringMessage(fanoutBatch));

                ackAll(contributing);
            } catch (Exception e) {
                log.error("ClickHouse insert failed for tenant {}: {}", tenantId, e.getMessage(), e);
                contributing.forEach(consumer::negativeAcknowledge);
            }
        }

        // Recursively call receiveMessages to continue receiving messages asynchronously
        receiveMessages();
    }

    private void ackAll(List<Message<DataWrapperBin>> msgs) {
        for (Message<DataWrapperBin> m : msgs) {
            try {
                consumer.acknowledge(m);
            } catch (PulsarClientException e) {
                // Ack failure means the broker didn't confirm; Pulsar's ackTimeout will redeliver
                // the message. Log and keep going so we don't leak the rest of the batch.
                log.warn("Ack failed for message {}: {}", m.getMessageId(), e.getMessage());
            }
        }
    }

    /**
     * For every subscription bound to a timeseries in this batch, publish the matching
     * {@link DataCollectionString} to the shared fanout topic keyed by the subscription
     * externalId. The broker-side entry filter then dispatches each message only to the
     * Pulsar subscription whose {@code filter.key} property matches that key. Looks up
     * subscriptions via the in-memory {@link SubscriptionCache} — no DB call on the hot path.
     * Best-effort: failures are logged but do not block the ClickHouse write or message ack.
     */
    private void forwardToSubscriptionTopics(DataWrapperMessage batch) {
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
                        .orderingKey(externalId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
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
                        // if batches are single-partition-key — which forwardToSubscriptionTopics
                        // guarantees by setting orderingKey equal to the partition key.
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

    @PreDestroy
    public void cleanup() {
        // Flip isRunning so the receive loop stops scheduling new batches instead of recursing into
        // an already-closed consumer.
        isRunning = false;

        // Drain in-flight inserts + acks on the executor BEFORE closing the consumer. Closing first
        // would abandon a running ClickHouse insert whose messages aren't acked yet: Pulsar redelivers
        // them on restart and the reprocess re-fires fan-out (duplicate live-tail). Mirrors the
        // drain-before-close ordering in BatchedEventsListener.cleanup.
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Datapoint inserts did not drain in 30s; forcing shutdown.");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executorService.shutdownNow();
            }
        }

        // Flush pending fan-out sends the drained inserts issued, then drop the producers.
        for (Map.Entry<String, Producer<DataWrapperMessage>> entry : fanoutProducers.entrySet()) {
            try {
                entry.getValue().flush();
                entry.getValue().closeAsync();
            } catch (PulsarClientException e) {
                log.warn("Failed to flush fanout producer for tenant {}: {}", entry.getKey(), e.getMessage());
            }
        }

        if (consumer != null) {
            try {
                consumer.closeAsync().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Datapoints consumer close failed: {}", e.getMessage());
            }
        }
    }

    static class DatapointsThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        DatapointsThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

}
