// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.responses.DataCollectionBin;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.clickhouse.DatapointBinaryConverter;
import ai.intellistream.datahub.clickhouse.ClickHouseDatapointService;
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
    // The fan-out to WebSocket subscriptions, shared with the binary frames listener.
    private final SubscriptionFanout subscriptionFanout;
    private ExecutorService executorService;

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
                subscriptionFanout.forward(DatapointBinaryConverter.toStringMessage(fanoutBatch));

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

        // The fan-out producers are flushed by SubscriptionFanout, destroyed after this bean.

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
