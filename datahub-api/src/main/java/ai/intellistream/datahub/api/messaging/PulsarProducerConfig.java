// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging;

import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.pulsar.ResourceCudMessage;
import ai.intellistream.datahub.pulsar.SubscriptionNotifyMessage;
import ai.intellistream.datahub.pulsar.TopicNames;
import org.apache.pulsar.client.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@DependsOn("instanceLock")
public class PulsarProducerConfig {

    // Partition the events CUD stream and the http request-log firehose so neither is pinned to a
    // single broker. events/cud-events is keyed by tenantId (see AfterCommitMessagePublisher), so
    // a tenant's CUD stays on one partition and stays ordered; http/message is an unkeyed,
    // fire-and-forget String firehose with no ordering requirement. Increase-only — start with
    // headroom.
    private static final int EVENTS_PARTITIONS = 16;
    private static final int HTTP_MESSAGE_PARTITIONS = 16;

    // all-datapoints is the single ingest funnel for every datapoint write. As a partitioned
    // topic it spreads across brokers instead of being pinned to one (a non-partitioned topic is
    // owned by exactly one broker, which caps throughput regardless of cluster size). With no
    // message key set the producer routes RoundRobinPartition across partitions, which is correct
    // here: the consumer uses a Shared subscription (no ordering guarantee) and ClickHouse orders
    // by timestamp on read. Partition count is increase-only in Pulsar — it can be raised later
    // but never lowered, and raising it rehashes keys — so start with headroom.
    private static final int ALL_DATAPOINTS_PARTITIONS = 16;

    // Per-instance suffix so several API instances on the same shared topic get distinct producer
    // names instead of colliding (the broker rejects a duplicate producer name per topic).
    private final String instanceSuffix;

    private final PulsarClient pulsarClient;
    private final PartitionedTopicProvisioner topicProvisioner;
    private final TopicNames topicNames;

    // --- all-datapoints producer backpressure / batching knobs (override via application config) ---
    // More in-flight headroom lets the producer absorb bursts instead of throwing
    // ProducerQueueIsFullError and shedding datapoints; the client-wide memoryLimit (PulsarConfig)
    // is the authoritative backstop.
    @Value("${datahub.pulsar.datapoints.max-pending-messages:5000}")
    private int maxPendingMessages;
    @Value("${datahub.pulsar.datapoints.max-pending-messages-across-partitions:50000}")
    private int maxPendingMessagesAcrossPartitions;
    @Value("${datahub.pulsar.datapoints.batching-max-publish-delay-ms:10}")
    private int batchingMaxPublishDelayMs;
    // Safe to enable now. This defaulted to false because the send ran inside a @Transactional
    // method, where blocking on a full queue held a pooled DB connection and could exhaust the
    // pool. TimeseriesService.insertDatapoints no longer publishes inside a transaction, so
    // blocking only parks the request thread, and the app runs on virtual threads.
    //
    // Still false by default because it is an operational choice, not a correctness one: true
    // applies backpressure to clients (slow requests) while false sheds datapoints
    // (ProducerQueueIsFullError). Prefer true for ingest you cannot afford to lose, but measure
    // the effect on request latency under a real burst first.
    @Value("${datahub.pulsar.datapoints.block-if-queue-full:false}")
    private boolean blockIfQueueFull;

    public PulsarProducerConfig(PulsarClient pulsarClient, PartitionedTopicProvisioner topicProvisioner,
                                TopicNames topicNames, AppInstanceId appInstanceId) {
        this.pulsarClient = pulsarClient;
        this.topicProvisioner = topicProvisioner;
        this.topicNames = topicNames;
        this.instanceSuffix = "-" + appInstanceId.get();
    }

    @Bean(name = "resourceMessageProducer")
    public Producer<ResourceCudMessage> produceResourceMessage() throws PulsarClientException {
        return pulsarClient
                .newProducer(Schema.AVRO(ResourceCudMessage.class))
                .topic(topicNames.getResourceTopicName())
                .accessMode(ProducerAccessMode.Shared)
                .compressionType(CompressionType.ZSTD)
                .producerName("reqlog-resource-producer" + instanceSuffix)
                .sendTimeout(10, TimeUnit.SECONDS)
                .create();
    }

    @Bean(name = "eventMessageProducer")
    public Producer<EventCudMessage> produceEventMessage() throws PulsarClientException {
        // Provision partitioned BEFORE this eager producer connects, else it auto-creates the
        // topic non-partitioned and partitioning can't be applied without a migration.
        topicProvisioner.ensurePartitioned(topicNames.getEventsTopicName(), EVENTS_PARTITIONS);
        return pulsarClient
                .newProducer(Schema.AVRO(EventCudMessage.class))
                .topic(topicNames.getEventsTopicName())
                .accessMode(ProducerAccessMode.Shared)
                .compressionType(CompressionType.ZSTD)
                .producerName("reqlog-event-producer" + instanceSuffix)
                .sendTimeout(10, TimeUnit.SECONDS)
                .create();
    }

    @Bean(name = "subscriptionNotifyProducer")
    public Producer<SubscriptionNotifyMessage> produceSubscriptionNotifyMessage() throws PulsarClientException {
        return pulsarClient
                .newProducer(Schema.AVRO(SubscriptionNotifyMessage.class))
                .topic(topicNames.getSubscriptionNotifyTopicName())
                .accessMode(ProducerAccessMode.Shared)
                .compressionType(CompressionType.ZSTD)
                .producerName("reqlog-subscription-notify-producer" + instanceSuffix)
                .sendTimeout(10, TimeUnit.SECONDS)
                .create();
    }

    @Bean(name = "allDatapointProducer")
    public Producer<DataWrapperBin> produceAllDatapointMessage() throws PulsarClientException {
        // Provision partitioned BEFORE this eager producer connects (see eventMessageProducer).
        topicProvisioner.ensurePartitioned(topicNames.getAllDatapointsTopicName(), ALL_DATAPOINTS_PARTITIONS);
        return pulsarClient
                .newProducer(Schema.AVRO(DataWrapperBin.class))
                .topic(topicNames.getAllDatapointsTopicName())
                .accessMode(ProducerAccessMode.Shared)
                .compressionType(CompressionType.ZSTD)
                .producerName("all-dp-producer" + instanceSuffix)
                .blockIfQueueFull(blockIfQueueFull)
                .maxPendingMessages(maxPendingMessages)
                .maxPendingMessagesAcrossPartitions(maxPendingMessagesAcrossPartitions)
                .enableBatching(true)
                .batchingMaxPublishDelay(batchingMaxPublishDelayMs, TimeUnit.MILLISECONDS)
                .sendTimeout(6, TimeUnit.SECONDS)
                .create();
    }

    @Bean(name = "httpMessageProducer")
    public Producer<String> produceHttpMessage() throws PulsarClientException {
        // Provision partitioned BEFORE this eager producer connects (see eventMessageProducer).
        topicProvisioner.ensurePartitioned(topicNames.getHttpMessageTopicName(), HTTP_MESSAGE_PARTITIONS);
        return pulsarClient
                .newProducer(Schema.STRING)
                .topic(topicNames.getHttpMessageTopicName())
                .accessMode(ProducerAccessMode.Shared)
                .compressionType(CompressionType.ZSTD)
                .producerName("reqlog-http-msg-producer" + instanceSuffix)
                .sendTimeout(10, TimeUnit.SECONDS)
                .create();
    }
}
