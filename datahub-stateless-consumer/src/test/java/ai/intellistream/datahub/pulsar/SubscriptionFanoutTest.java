// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionFanoutTest {

    private static final String TENANT = "tenant-a";
    private static final String TOPIC = "persistent://tenant-a/subscriptions/fanout";
    private static final int PARTITIONS = 8;

    /**
     * A fanout partition whose producer can never be created — what the broker does to a partition
     * over its backlog quota — must not hold up the caller. {@code forward()} runs on the
     * datapoints-listener pool after the ClickHouse write, so blocking here stalls the ingest that
     * fan-out is meant to be decoupled from.
     */
    @Test
    void forwardDoesNotBlockWhenAProducerCannotBeCreated() {
        Harness h = new Harness();
        h.producerNeverReady();

        long start = System.nanoTime();
        h.fanout.forward(h.batch("sub-a"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 1_000,
                "forward() blocked for " + elapsedMs + "ms on a producer that never becomes ready");
    }

    /**
     * The point of per-partition producers: one partition the broker refuses must not stop
     * subscriptions routed to any other partition. Before this, a single partitioned producer made
     * the tenant's whole fan-out all-or-nothing.
     */
    @Test
    void aBlockedPartitionDoesNotStopTheOthers() {
        String blocked = "sub-on-blocked";
        int blockedPartition = SubscriptionFanout.partitionFor(blocked, PARTITIONS);
        String healthy = externalIdOnAnyPartitionOtherThan(blockedPartition);

        Harness h = new Harness();
        h.blockProducerOn(partitionTopic(blockedPartition));

        h.fanout.forward(h.batch(blocked));
        h.fanout.forward(h.batch(healthy));

        assertEquals(List.of(partitionTopic(SubscriptionFanout.partitionFor(healthy, PARTITIONS))),
                h.sentOnTopics,
                "the subscription on a healthy partition should still have been published");
    }

    /**
     * A partition the broker refuses must back off, not be retried on every batch. The failure
     * arrives as an already-failed future, so the completion callback runs inline.
     */
    @Test
    void aRefusedPartitionBacksOffInsteadOfRetryingEveryBatch() {
        String blocked = "sub-on-blocked";
        Harness h = new Harness();
        h.blockProducerOn(partitionTopic(SubscriptionFanout.partitionFor(blocked, PARTITIONS)));

        h.fanout.forward(h.batch(blocked));
        h.fanout.forward(h.batch(blocked));
        h.fanout.forward(h.batch(blocked));

        verify(h.producerBuilder, times(1)).createAsync();
    }

    /**
     * And once the broker accepts producers again — the operator cleared the backlog — the
     * partition must recover on its own. A failed build left cached would leave the partition dead
     * until the consumer was restarted.
     */
    @Test
    void aRefusedPartitionRecoversOnceTheBrokerAcceptsProducersAgain() {
        String blocked = "sub-on-blocked";
        String blockedTopic = partitionTopic(SubscriptionFanout.partitionFor(blocked, PARTITIONS));

        Harness h = new Harness();
        h.fanout.retryBackoffMs = 0;   // don't make the test wait out the real backoff
        h.blockProducerOn(blockedTopic);

        h.fanout.forward(h.batch(blocked));
        assertEquals(List.of(), h.sentOnTopics, "the refused partition cannot publish yet");

        h.unblockProducers();
        h.fanout.forward(h.batch(blocked));

        assertEquals(List.of(blockedTopic), h.sentOnTopics,
                "the partition should have retried and published once the broker accepted a producer");
    }

    /**
     * While a build is in flight, later batches must fall straight through rather than each queuing
     * another create against a broker that is already refusing them.
     */
    @Test
    void onlyOneProducerBuildIsStartedPerPartitionWhileOneIsInFlight() {
        Harness h = new Harness();
        h.producerNeverReady();

        h.fanout.forward(h.batch("sub-a"));
        h.fanout.forward(h.batch("sub-a"));
        h.fanout.forward(h.batch("sub-a"));

        verify(h.producerBuilder, times(1)).createAsync();
    }

    /**
     * Batches published while a producer is still building must be sent once it arrives, not
     * dropped. The first datapoints written after a consumer starts land in exactly that window —
     * dropping them makes a subscriber's first write vanish with no error anywhere.
     */
    @Test
    void batchesQueuedWhileAProducerIsBuildingAreSentOnceItArrives() {
        Harness h = new Harness();
        Runnable completeProducers = h.producerPending();

        h.fanout.forward(h.batch("sub-a"));
        assertEquals(List.of(), h.sentOnTopics, "nothing can be sent before the producer exists");

        completeProducers.run();

        assertEquals(List.of(partitionTopic(SubscriptionFanout.partitionFor("sub-a", PARTITIONS))),
                h.sentOnTopics, "the queued batch should have been sent once the producer arrived");
    }

    /** A tenant with no {@code pulsar.tenant} is reported, not retried on every batch. */
    @Test
    void unconfiguredTenantDoesNotLookUpPartitions() {
        Harness h = new Harness();
        when(h.topicNames.getSubscriptionFanoutTopicName(TENANT))
                .thenThrow(new IllegalStateException("Tenant " + TENANT + " has no 'pulsar.tenant' configured"));

        h.fanout.forward(h.batch("sub-a"));
        h.fanout.forward(h.batch("sub-a"));

        verify(h.client, times(0)).getPartitionsForTopic(anyString());
    }

    /**
     * Routing must stay byte-identical to Pulsar's SinglePartition router under JavaStringHash, or
     * taking it over would silently move existing subscriptions to a different partition — where
     * their durable cursor's backlog, and their ordering, do not follow.
     */
    @Test
    void routingMatchesPulsarsSinglePartitionRouter() {
        for (String id : List.of("autoencoder_inputs_fleet", "debug_probe_tmp", "a", "")) {
            assertEquals((id.hashCode() & Integer.MAX_VALUE) % PARTITIONS,
                    SubscriptionFanout.partitionFor(id, PARTITIONS), id);
        }
    }

    // --- helpers ---

    private static String partitionTopic(int partition) {
        return TOPIC + "-partition-" + partition;
    }

    private static String externalIdOnAnyPartitionOtherThan(int partition) {
        return IntStream.range(0, 1000)
                .mapToObj(i -> "sub-" + i)
                .filter(id -> SubscriptionFanout.partitionFor(id, PARTITIONS) != partition)
                .findFirst()
                .orElseThrow();
    }

    private static long idFor(String subscriptionExternalId) {
        return subscriptionExternalId.hashCode() & 0xFFFF;
    }

    /** A SubscriptionFanout wired to mocks, recording the partition topic each send landed on. */
    @SuppressWarnings("unchecked")
    private static final class Harness {
        final PulsarClient client = mock(PulsarClient.class);
        final TopicNames topicNames = mock(TopicNames.class);
        final SubscriptionCache cache = mock(SubscriptionCache.class);
        final ProducerBuilder<Object> producerBuilder = mock(ProducerBuilder.class, Answers.RETURNS_SELF);
        final List<String> sentOnTopics = new ArrayList<>();
        final Map<Long, Set<String>> bindings = new HashMap<>();
        final SubscriptionFanout fanout;

        private String topicOfProducerUnderConstruction;

        Harness() {
            when(topicNames.getSubscriptionFanoutTopicName(TENANT)).thenReturn(TOPIC);
            when(client.getPartitionsForTopic(TOPIC)).thenReturn(CompletableFuture.completedFuture(
                    IntStream.range(0, PARTITIONS)
                            .mapToObj(SubscriptionFanoutTest::partitionTopic)
                            .toList()));
            when(client.newProducer(any(Schema.class))).thenReturn(producerBuilder);
            // Remember which partition topic the builder was pointed at, so createAsync() can hand
            // back a producer that records sends against it.
            when(producerBuilder.topic(anyString())).thenAnswer(inv -> {
                topicOfProducerUnderConstruction = inv.getArgument(0);
                return producerBuilder;
            });
            when(producerBuilder.createAsync())
                    .thenAnswer(inv -> CompletableFuture.completedFuture(
                            recordingProducer(topicOfProducerUnderConstruction)));
            // Each timeseries id resolves to the subscriptions bound to it by batch().
            when(cache.getSubscriptionExternalIds(anyString(), any()))
                    .thenAnswer(inv -> bindings.getOrDefault(inv.<Long>getArgument(1), Set.of()));
            fanout = new SubscriptionFanout(client, topicNames, cache, new AppInstanceId("host-numa0"));
        }

        /** A one-timeseries batch whose timeseries is bound to exactly this subscription. */
        DataWrapperMessage batch(String subscriptionExternalId) {
            long timeseriesId = idFor(subscriptionExternalId);
            bindings.put(timeseriesId, Set.of(subscriptionExternalId));
            DataCollectionString item = new DataCollectionString();
            item.setId(timeseriesId);
            item.setExternalId("ts-" + subscriptionExternalId);
            return new DataWrapperMessage(EventObject.DATAPOINTS, EventAction.CREATE, List.of(item), TENANT);
        }

        /** No producer ever becomes ready, whatever partition it is for. */
        void producerNeverReady() {
            when(producerBuilder.createAsync()).thenReturn(new CompletableFuture<>());
        }

        /**
         * Hold every producer pending; the returned action completes them, standing in for the
         * broker finally handing one back.
         */
        Runnable producerPending() {
            List<CompletableFuture<Producer<DataWrapperMessage>>> pending = new ArrayList<>();
            List<String> topics = new ArrayList<>();
            when(producerBuilder.createAsync()).thenAnswer(inv -> {
                CompletableFuture<Producer<DataWrapperMessage>> f = new CompletableFuture<>();
                pending.add(f);
                topics.add(topicOfProducerUnderConstruction);
                return f;
            });
            return () -> {
                for (int i = 0; i < pending.size(); i++) {
                    pending.get(i).complete(recordingProducer(topics.get(i)));
                }
            };
        }

        /** Only this partition topic refuses producers; the rest behave normally. */
        void blockProducerOn(String blockedPartitionTopic) {
            when(producerBuilder.createAsync()).thenAnswer(inv -> {
                String topic = topicOfProducerUnderConstruction;
                if (blockedPartitionTopic.equals(topic)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Cannot create producer on topic with backlog quota exceeded"));
                }
                return CompletableFuture.completedFuture(recordingProducer(topic));
            });
        }

        /** Every partition accepts producers again, as after an operator clears the backlog. */
        void unblockProducers() {
            when(producerBuilder.createAsync()).thenAnswer(inv ->
                    CompletableFuture.completedFuture(recordingProducer(topicOfProducerUnderConstruction)));
        }

        private Producer<DataWrapperMessage> recordingProducer(String topic) {
            Producer<DataWrapperMessage> producer = mock(Producer.class);
            TypedMessageBuilder<DataWrapperMessage> message =
                    mock(TypedMessageBuilder.class, Answers.RETURNS_SELF);
            when(producer.newMessage()).thenReturn(message);
            when(message.sendAsync()).thenAnswer(inv -> {
                sentOnTopics.add(topic);
                return CompletableFuture.completedFuture(null);
            });
            return producer;
        }
    }
}
