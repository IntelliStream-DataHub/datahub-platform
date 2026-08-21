// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.repositories.event.DatasetValue;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Consumes {@link EventCudMessage}s and writes them to ClickHouse.
 *
 * <p>Keyed worker pool: Pulsar's {@link SubscriptionType#Key_Shared} delivers all messages
 * for a given key to this single consumer instance in order. Inside the JVM, a dispatcher
 * thread routes each message to one of {@link #WORKER_COUNT} worker threads by
 * {@code Math.floorMod(tenantId.hashCode(), WORKER_COUNT)}. Same tenant → same worker,
 * which serializes {@code CREATE → UPDATE → DELETE} for any event (events are
 * tenant-scoped, so per-tenant ordering is per-event ordering). Different tenants may land
 * on different workers and process in parallel — which is what gives us ClickHouse insert
 * concurrency without the reordering the previous dual-{@code batchReceiveAsync} design had.
 *
 * <p>Each worker drains its queue, re-runs the same tenant-grouped bulk-insert logic as
 * before, and acks/nacks per message on success/failure of its own bulk insert — the
 * dead-letter policy handles persistent failures.
 */
@Service
@Slf4j
public class BatchedEventsListener {

    /**
     * Workers. Sized for concurrent ClickHouse bulk-insert throughput; the natural ceiling
     * is ClickHouse's own write concurrency, not CPU. Going above ~8 rarely helps because
     * each worker's bulk insert is already large.
     */
    private static final int WORKER_COUNT = 4;

    /**
     * Bounded queue per worker so a slow ClickHouse write naturally back-pressures the
     * dispatcher, which in turn stops acking and lets Pulsar's flow control kick in.
     */
    private static final int WORKER_QUEUE_CAPACITY = 1024;

    //  volatile: stopConsumer() flips this from another thread and all loops must see it
    //  on the next iteration without caching a stale true.
    private volatile boolean isRunning = false;

    private final PulsarClient pulsarClient;
    private Consumer<EventCudMessage> consumer;
    private final ClickHouseEventService clickHouseEventService;
    private final EventDimensionRepository eventDimensionRepository;
    private final TopicNames topicNames;

    private final List<Worker> workers = new ArrayList<>(WORKER_COUNT);
    private Thread dispatcherThread;

    public BatchedEventsListener(
            PulsarClient pulsarClient,
            ClickHouseEventService clickHouseEventService,
            EventDimensionRepository eventDimensionRepository,
            TopicNames topicNames
    ) {
        this.pulsarClient = pulsarClient;
        this.clickHouseEventService = clickHouseEventService;
        this.eventDimensionRepository = eventDimensionRepository;
        this.topicNames = topicNames;
    }

    @PostConstruct
    public void init() {
        try {
            var brp = BatchReceivePolicy.builder()
                    .maxNumMessages(-1)
                    .maxNumBytes(10 * 1024 * 1024)
                    .timeout(500, TimeUnit.MILLISECONDS)
                    .build();

            // Exclusive subscription + listener nack means one persistently failing event-CUD
            // message would otherwise block the whole per-tenant event stream at the head.
            // Cap redelivery at 10 so the broker diverts poison messages to <topic>-<sub>-DLQ
            // and later events keep flowing.
            var deadLetterPolicy = DeadLetterPolicy.builder()
                    .maxRedeliverCount(10)
                    .build();

            consumer = pulsarClient.newConsumer(Schema.AVRO(EventCudMessage.class))
                    .subscriptionName("event-cud-sub-clickhouse")
                    // See GraphEventNeo4jConsumer: a NEW subscription defaults to Latest and
                    // silently drops anything published before this consumer attaches.
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                    .consumerName("batched-events-ch-consumer")
                    .batchReceivePolicy(brp)
                    .topic(topicNames.getEventsTopicName())
                    .ackTimeout(120, TimeUnit.SECONDS)
                    // Key_Shared preserves per-key order even when we later scale to
                    // multiple consumer JVMs. Producer sets key = tenantId, so all CUD for
                    // a tenant lands on the same consumer instance; within this JVM the
                    // dispatcher fans out by the same key.
                    .subscriptionType(SubscriptionType.Key_Shared)
                    .deadLetterPolicy(deadLetterPolicy)
                    .autoUpdatePartitionsInterval(30, TimeUnit.SECONDS)
                    .subscribe();

            isRunning = true;

            for (int i = 0; i < WORKER_COUNT; i++) {
                Worker w = new Worker(i);
                workers.add(w);
                w.thread.start();
            }

            dispatcherThread = new Thread(this::dispatchLoop,
                    "events-dispatcher");
            dispatcherThread.setDaemon(true);
            dispatcherThread.start();

            log.debug("Started events consumer with {} workers.", WORKER_COUNT);

        } catch (Exception e) {
            // Fail fast. Swallowing this leaves the headless app "up" but consuming no events — a
            // brief Pulsar outage at boot would then stall event ingestion permanently with no health
            // signal. Abort startup so the orchestrator restarts us. (Mirrors SubscriptionNotifyListener.)
            throw new IllegalStateException("Failed to start the events consumer; refusing to start.", e);
        }
    }

    /**
     * Pull a batch from Pulsar, fan out to workers by tenant hash. Back-pressure comes
     * from the bounded worker queues: {@code put()} blocks when a worker is behind, which
     * stops acks flowing, which eventually stops broker delivery.
     */
    private void dispatchLoop() {
        while (isRunning) {
            try {
                Messages<EventCudMessage> messages = consumer.batchReceive();
                if (messages == null || messages.size() == 0) continue;

                for (Message<EventCudMessage> msg : messages) {
                    try {
                        EventCudMessage value = msg.getValue();
                        String tenantId = value != null ? value.getTenantId() : null;
                        int workerIdx = routeToWorker(tenantId);
                        workers.get(workerIdx).queue.put(msg);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        // Malformed payload or similar per-message fault — nack so Pulsar
                        // redelivers, and the DLQ catches it if it keeps failing.
                        log.error("Dispatch failed for message {}: {}", msg.getMessageId(), e.getMessage(), e);
                        consumer.negativeAcknowledge(msg);
                    }
                }
            } catch (PulsarClientException.AlreadyClosedException closed) {
                // Expected during shutdown — the consumer was closed out from under us.
                return;
            } catch (PulsarClientException e) {
                if (isRunning) {
                    log.error("Events dispatcher receive failed: {}", e.getMessage(), e);
                }
            }
        }
    }

    private static int routeToWorker(String tenantId) {
        // Null tenants land on worker 0 deterministically; still ordered with themselves.
        if (tenantId == null) return 0;
        return Math.floorMod(tenantId.hashCode(), WORKER_COUNT);
    }

    /** One worker owns a stable slice of tenants and processes them strictly in order. */
    /** Test seam: run one batch through a worker's grouping/dispatch without starting threads. */
    void processBatchForTest(List<Message<EventCudMessage>> batch, Consumer<EventCudMessage> consumer) {
        this.consumer = consumer;
        new Worker(0).process(batch);
    }

    private final class Worker {
        final int idx;
        final BlockingQueue<Message<EventCudMessage>> queue;
        final Thread thread;

        Worker(int idx) {
            this.idx = idx;
            this.queue = new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY);
            this.thread = new Thread(this::run, "events-worker-" + idx);
            this.thread.setDaemon(true);
        }

        void run() {
            // Keep draining until shutdown AND queue is empty — this is what guarantees
            // in-flight messages from Pulsar land in ClickHouse before the JVM exits.
            while (isRunning || !queue.isEmpty()) {
                try {
                    Message<EventCudMessage> first = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (first == null) continue;

                    List<Message<EventCudMessage>> drained = new ArrayList<>();
                    drained.add(first);
                    queue.drainTo(drained);

                    process(drained);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    // Defensive — keep the worker alive so a single bad batch doesn't
                    // silently take the partition offline.
                    log.error("Events worker {} unexpected error: {}", idx, t.getMessage(), t);
                }
            }
        }

        /**
         * Group this worker's slice of messages by tenant + action and issue one bulk
         * insert per (tenant, action). Identical logic to the original single-thread path
         * — just scoped to this worker's tenants rather than the whole batch.
         */
        void process(List<Message<EventCudMessage>> batch) {
            Map<String, EventCudMessage> createData = new HashMap<>();
            Map<String, EventCudMessage> updateData = new HashMap<>();
            Map<String, EventCudMessage> deleteData = new HashMap<>();
            // CREATE/UPDATE events per tenant, used to feed the Postgres type/sub_type/status/source
            // dimension tables after the ClickHouse write. Both actions carry the full EventModels;
            // DELETE never adds dimension values (retraction is the reconciliation job's job).
            Map<String, List<EventModel>> dimEventsByTenant = new HashMap<>();

            // Only messages that group cleanly get acked on success. Malformed ones are nacked
            // below and must never be acked afterwards: the ack would land before the nack's
            // redelivery timer fires, the redelivery count would never climb, and the DLQ
            // policy could never divert the poison message.
            List<Message<EventCudMessage>> toAck = new ArrayList<>(batch.size());

            for (Message<EventCudMessage> msg : batch) {
                try {
                    EventCudMessage message = msg.getValue();
                    if (message.getEventObject() == EventObject.EVENT) {
                        switch (message.getEventAction()) {
                            // The first message per (tenant, action) becomes the accumulator, so
                            // only append when putIfAbsent found an existing one — appending the
                            // first message's own list onto itself would double its payload.
                            case CREATE -> {
                                EventCudMessage prev = createData.putIfAbsent(message.getTenantId(), message);
                                if (prev != null) {
                                    prev.getEvents().addAll(message.getEvents());
                                }
                                dimEventsByTenant.computeIfAbsent(message.getTenantId(), k -> new ArrayList<>())
                                        .addAll(message.getEvents());
                            }
                            case UPDATE -> {
                                EventCudMessage prev = updateData.putIfAbsent(message.getTenantId(), message);
                                if (prev != null) {
                                    prev.getUpdateEvents().addAll(message.getUpdateEvents());
                                    // The resolved models travel with the patch forms: the
                                    // related-resource mutation reads its column values from them
                                    // rather than from the raw form. Drop them and every message
                                    // after the first in a batch loses its related resources.
                                    prev.getEvents().addAll(message.getEvents());
                                }
                                dimEventsByTenant.computeIfAbsent(message.getTenantId(), k -> new ArrayList<>())
                                        .addAll(message.getEvents());
                            }
                            case DELETE -> {
                                EventCudMessage prev = deleteData.putIfAbsent(message.getTenantId(), message);
                                if (prev != null) {
                                    prev.getEvents().addAll(message.getEvents());
                                }
                            }
                        }
                    }
                    // Non-EVENT objects fall through to here too: ack them rather than
                    // redeliver a message this listener will never be able to process.
                    toAck.add(msg);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    consumer.negativeAcknowledge(msg);
                }
            }

            try {
                // A worker's batch can mix tenants (several tenants hash to the same worker), so set
                // TenantContext per tenant-group around each bulk insert rather than once per worker.
                // The ClickHouse writes are already tenant-explicit; this keeps TenantContext correct
                // for any per-tenant lookup the event path might grow later.
                for (var cd : createData.values()) {
                    if (!cd.getEvents().isEmpty()) {
                        TenantContext.runWith(cd.getTenantId(), () -> clickHouseEventService.createEvents(cd));
                    }
                }
                for (var ud : updateData.values()) {
                    if (!ud.getUpdateEvents().isEmpty()) {
                        TenantContext.runWith(ud.getTenantId(), () -> clickHouseEventService.updateEvents(ud));
                    }
                }
                for (var dd : deleteData.values()) {
                    if (!dd.getEvents().isEmpty()) {
                        TenantContext.runWith(dd.getTenantId(), () -> clickHouseEventService.deleteEvents(dd));
                    }
                }

                // After the events are safely in ClickHouse, record their type/sub_type/status/source in the
                // Postgres dimension tables. Best-effort and never throws, so it can't turn an
                // already-persisted batch into a nack — the reconciliation job repairs any miss.
                for (var entry : dimEventsByTenant.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        TenantContext.runWith(entry.getKey(), () -> updateDimensions(entry.getValue()));
                    }
                }

                // Per-message async ack so one Pulsar ack stall doesn't hold the worker.
                for (Message<EventCudMessage> m : toAck) {
                    consumer.acknowledgeAsync(m);
                }
            } catch (Exception e) {
                log.error("Events worker {} bulk insert failed: {}", idx, e.getMessage(), e);
                for (Message<EventCudMessage> m : batch) {
                    consumer.negativeAcknowledge(m);
                }
            }
        }
    }

    /**
     * Best-effort upsert of these events' type/sub_type/status/source into the Postgres dimension
     * tables. Swallows everything: the events are already in ClickHouse, so a dimension hiccup must not
     * nack the batch, and the weekly reconciliation job repairs any gap. Must be called within a
     * TenantContext so the JPA routing datasource targets the right tenant database.
     */
    private void updateDimensions(List<EventModel> events) {
        try {
            eventDimensionRepository.upsert(EventDimensionRepository.Dimension.TYPE,
                    collectDimension(events, EventModel::getType));
            eventDimensionRepository.upsert(EventDimensionRepository.Dimension.SUB_TYPE,
                    collectDimension(events, EventModel::getSubType));
            eventDimensionRepository.upsert(EventDimensionRepository.Dimension.STATUS,
                    collectDimension(events, EventModel::getStatus));
            eventDimensionRepository.upsert(EventDimensionRepository.Dimension.SOURCE,
                    collectDimension(events, EventModel::getSource));
        } catch (RuntimeException e) {
            log.warn("Failed to update event dimension tables (reconciliation will repair): {}", e.getMessage());
        }
    }

    private static Collection<DatasetValue> collectDimension(List<EventModel> events, Function<EventModel, String> getValue) {
        List<DatasetValue> values = new ArrayList<>();
        for (EventModel em : events) {
            String value = getValue.apply(em);
            if (value != null && !value.isBlank()) {
                // null dataSetId -> 0, the "no dataset" sentinel used in ClickHouse events.data_set_id.
                long dataSetId = em.getDataSetId() == null ? 0L : em.getDataSetId();
                values.add(new DatasetValue(dataSetId, value));
            }
        }
        return values;
    }

    public void stopConsumer() {
        isRunning = false;
    }

    @PreDestroy
    public void cleanup() {
        // Halt accept first so the dispatcher exits on its next loop iteration (at most
        // one BatchReceivePolicy.timeout later).
        stopConsumer();

        if (dispatcherThread != null) {
            try {
                dispatcherThread.join(5_000);
                if (dispatcherThread.isAlive()) {
                    // Shouldn't happen — batchReceive() has a 500ms policy timeout — but
                    // if the JVM is stuck, interrupt to unblock.
                    dispatcherThread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Let workers finish draining their queues before the consumer closes, so
        // in-flight acks still succeed.
        for (Worker w : workers) {
            try {
                w.thread.join(30_000);
                if (w.thread.isAlive()) {
                    log.warn("Events worker {} did not drain in 30s, interrupting.", w.idx);
                    w.thread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (consumer != null) {
            try {
                consumer.closeAsync().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Events consumer close failed: {}", e.getMessage());
            }
        }
    }
}
