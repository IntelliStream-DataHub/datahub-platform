// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrame.Run;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameMerger;
import ai.intellistream.datahub.api.binary.PayloadCodec;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.clickhouse.ClickHouseDatapointService;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.BatchReceivePolicy;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Consumes the binary datapoint frames. Every message is one frame exactly as the client sent it,
 * still compressed; this listener decompresses and re-validates it, merges everything for one
 * tenant and value type in the batch into one Arrow stream of large blocks, streams that to
 * ClickHouse, and decodes only the series with live subscribers for the fan-out.
 */
@Service
@Slf4j
// See BatchedDatapointsListener: the notify listener fills the subscription cache first.
@DependsOn({"subscriptionNotifyListener"})
@RequiredArgsConstructor
public class BatchedDatapointBlocksListener {

    /** Rows per Arrow batch after the merge; ClickHouse's own insert block is about this size. */
    static final int MERGE_MAX_ROWS = 1_000_000;

    private volatile boolean isRunning = false;
    private final PulsarClient pulsarClient;
    private final ClickHouseDatapointService clickHouseDatapointService;
    private final TopicNames topicNames;
    private final SubscriptionCache subscriptionCache;
    private final SubscriptionFanout subscriptionFanout;
    private Consumer<byte[]> consumer;
    private ExecutorService executorService;
    private final PayloadCodec codec = new ZstdPayloadCodec();

    @PostConstruct
    public void init() {
        this.executorService = Executors.newFixedThreadPool(
                8, new BatchedDatapointsListener.DatapointsThreadFactory("datapoint-blocks-listener"));
        try {
            var brp = BatchReceivePolicy.builder()
                    .maxNumMessages(-1)
                    .maxNumBytes(20 * 1024 * 1024)
                    .timeout(500, TimeUnit.MILLISECONDS)
                    .build();
            var deadLetterPolicy = DeadLetterPolicy.builder()
                    .maxRedeliverCount(10)
                    .build();

            consumer = pulsarClient.newConsumer(Schema.BYTES)
                    .subscriptionName(TopicNames.ALL_DATAPOINT_BLOCKS_SUBSCRIPTION_NAME)
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                    .consumerName("batched-datapoint-blocks-ch-consumer")
                    .batchReceivePolicy(brp)
                    .topic(topicNames.getAllDatapointBlocksTopicName())
                    .ackTimeout(120, TimeUnit.SECONDS)
                    .subscriptionType(SubscriptionType.Shared)
                    .autoUpdatePartitionsInterval(30, TimeUnit.SECONDS)
                    .deadLetterPolicy(deadLetterPolicy)
                    .subscribe();

            startConsumer();
            log.debug("Started all-datapoint-blocks consumer.");
        } catch (Exception e) {
            // Fail fast, as the Avro listener does: a silent subscribe failure would stall ingest.
            throw new IllegalStateException("Failed to start the all-datapoint-blocks consumer; refusing to start.", e);
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
        if (!isRunning) {
            log.debug("Stopped all-datapoint-blocks consumer.");
            return;
        }
        consumer.batchReceiveAsync()
                .thenAcceptAsync(this::handleBlockMessages, this.executorService)
                .exceptionally(ex -> {
                    log.error("Failed to receive datapoint frames: {}", ex.getMessage());
                    receiveMessages();
                    return null;
                });
    }

    private record Parsed(Message<byte[]> message, String tenantId, List<DatapointFrame> frames) {
    }

    private record Group(String tenantId, DatapointValueType type) {
    }

    // Package-private so the merge, the per-group ack/nack and the fan-out can be unit-tested.
    void handleBlockMessages(Messages<byte[]> messages) {
        List<Parsed> parsed = decodeAll(messages);

        Map<Group, List<Parsed>> groups = new LinkedHashMap<>();
        for (Parsed p : parsed) {
            for (DatapointFrame f : p.frames()) {
                groups.computeIfAbsent(new Group(p.tenantId(), f.valueType()), g -> new ArrayList<>());
            }
        }
        for (Map.Entry<Group, List<Parsed>> entry : groups.entrySet()) {
            Group group = entry.getKey();
            List<DatapointFrame> frames = new ArrayList<>();
            List<Message<byte[]>> contributing = new ArrayList<>();
            for (Parsed p : parsed) {
                if (!p.tenantId().equals(group.tenantId())) continue;
                boolean contributes = false;
                for (DatapointFrame f : p.frames()) {
                    if (f.valueType() == group.type()) {
                        frames.add(f);
                        contributes = true;
                    }
                }
                if (contributes) contributing.add(p.message());
            }
            try {
                byte[] stream = FrameMerger.merge(group.type(), frames, MERGE_MAX_ROWS);
                clickHouseDatapointService.insertArrowStream(group.tenantId(), group.type(), stream);
                fanOut(group.tenantId(), group.type(), frames);
                // A message can carry frames of several types; it is acked once every group it
                // contributed to has succeeded, and a nack for one group wins over the acks.
                for (Message<byte[]> m : contributing) acknowledge(m);
            } catch (Exception e) {
                log.error("ClickHouse insert of {} frames failed for tenant {}: {}", group.type(), group.tenantId(), e.getMessage(), e);
                contributing.forEach(consumer::negativeAcknowledge);
            }
        }
        receiveMessages();
    }

    /** Parse and decompress every message; the frames were validated by the API, so a failure here nacks. */
    private List<Parsed> decodeAll(Messages<byte[]> messages) {
        List<Message<byte[]>> all = new ArrayList<>();
        messages.forEach(all::add);
        List<Parsed> parsed = new ArrayList<>(all.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Parsed>> pending = new ArrayList<>(all.size());
            for (Message<byte[]> msg : all) {
                pending.add(pool.submit(() -> decode(msg)));
            }
            for (int i = 0; i < pending.size(); i++) {
                Message<byte[]> msg = all.get(i);
                try {
                    parsed.add(pending.get(i).get());
                } catch (ExecutionException e) {
                    log.error("Rejecting datapoint frame message {}: {}", msg.getMessageId(), e.getCause().getMessage());
                    consumer.negativeAcknowledge(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consumer.negativeAcknowledge(msg);
                }
            }
        }
        return parsed;
    }

    private Parsed decode(Message<byte[]> msg) {
        String tenantId = msg.getProperty("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("message carries no tenantId property");
        }
        List<DatapointFrame> frames = DatapointFrame.parseEnvelopes(msg.getData());
        for (DatapointFrame f : frames) {
            f.decode(codec);
        }
        return new Parsed(msg, tenantId, frames);
    }

    /** Decode to the string shape only the runs some subscription is bound to. */
    private void fanOut(String tenantId, DatapointValueType type, List<DatapointFrame> frames) {
        List<DataCollectionString> items = new ArrayList<>();
        String valueType = type.name().toLowerCase(Locale.ROOT);
        for (DatapointFrame f : frames) {
            for (Run run : f.runs()) {
                if (subscriptionCache.getSubscriptionExternalIds(tenantId, run.id()).isEmpty()) continue;
                List<DatapointString> points = new ArrayList<>(run.rows());
                for (int r = run.from(); r < run.to(); r++) {
                    String iso = Instant.ofEpochMilli(f.timestamp(r)).atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    points.add(new DatapointString(iso, f.valueAsString(r)));
                }
                DataCollectionString item = new DataCollectionString();
                item.setId(run.id());
                item.setExternalId(run.externalId());
                item.setValueType(valueType);
                item.setDatapoints(points);
                items.add(item);
            }
        }
        if (items.isEmpty()) return;
        subscriptionFanout.forward(new DataWrapperMessage(EventObject.DATAPOINTS, EventAction.CREATE, items, tenantId));
    }

    private void acknowledge(Message<byte[]> m) {
        try {
            consumer.acknowledge(m);
        } catch (PulsarClientException e) {
            log.warn("Ack failed for message {}: {}", m.getMessageId(), e.getMessage());
        }
    }

    @PreDestroy
    public void cleanup() {
        isRunning = false;
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Datapoint frame inserts did not drain in 30s; forcing shutdown.");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executorService.shutdownNow();
            }
        }
        if (consumer != null) {
            try {
                consumer.closeAsync().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Datapoint frames consumer close failed: {}", e.getMessage());
            }
        }
    }
}
