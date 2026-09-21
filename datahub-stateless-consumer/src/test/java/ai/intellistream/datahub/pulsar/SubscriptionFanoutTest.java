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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionFanoutTest {

    private static final String TENANT = "tenant-a";

    /**
     * A fanout topic whose producer never arrives — the broker accepted the connection but does not
     * answer the create — must not hold up the caller. {@code forward()} runs on the datapoints-listener pool after the
     * ClickHouse write, so blocking here stalls ingest that fan-out is meant to be decoupled from.
     */
    @Test
    @SuppressWarnings("unchecked")
    void forwardDoesNotBlockWhenTheProducerCannotBeCreated() {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<Object> builder = mock(ProducerBuilder.class, Answers.RETURNS_SELF);
        when(client.newProducer(any(Schema.class))).thenReturn(builder);
        // Never completes: the broker accepted the connection but will not hand back a producer.
        when(builder.createAsync()).thenReturn(new CompletableFuture<>());

        SubscriptionFanout fanout = new SubscriptionFanout(
                client, topicNames(), cacheWith("sub-a"), new AppInstanceId("host-numa0"));

        long start = System.nanoTime();
        fanout.forward(batch());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 1_000,
                "forward() blocked for " + elapsedMs + "ms on a producer that never becomes ready");
    }

    /**
     * While a build is in flight, later batches must share it rather than each queuing another
     * create against a broker that is already refusing them.
     */
    @Test
    @SuppressWarnings("unchecked")
    void onlyOneProducerBuildIsStartedWhileOneIsInFlight() {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<Object> builder = mock(ProducerBuilder.class, Answers.RETURNS_SELF);
        when(client.newProducer(any(Schema.class))).thenReturn(builder);
        when(builder.createAsync()).thenReturn(new CompletableFuture<>());

        SubscriptionFanout fanout = new SubscriptionFanout(
                client, topicNames(), cacheWith("sub-a"), new AppInstanceId("host-numa0"));

        fanout.forward(batch());
        fanout.forward(batch());
        fanout.forward(batch());

        verify(builder, times(1)).createAsync();
    }

    /**
     * Batches published while the producer is still building must be sent once it arrives, not
     * dropped. The first datapoints written after a consumer starts land in exactly that window —
     * dropping them makes a subscriber's first write vanish with no error anywhere.
     */
    @Test
    @SuppressWarnings("unchecked")
    void batchesQueuedWhileTheProducerIsBuildingAreSentOnceItArrives() {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<Object> builder = mock(ProducerBuilder.class, Answers.RETURNS_SELF);
        when(client.newProducer(any(Schema.class))).thenReturn(builder);
        CompletableFuture<Producer<DataWrapperMessage>> pending = new CompletableFuture<>();
        when(builder.createAsync()).thenReturn((CompletableFuture) pending);

        Producer<DataWrapperMessage> producer = mock(Producer.class);
        TypedMessageBuilder<DataWrapperMessage> message = mock(TypedMessageBuilder.class, Answers.RETURNS_SELF);
        when(producer.newMessage()).thenReturn(message);
        AtomicInteger sends = new AtomicInteger();
        when(message.sendAsync()).thenAnswer(inv -> {
            sends.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        SubscriptionFanout fanout = new SubscriptionFanout(
                client, topicNames(), cacheWith("sub-a"), new AppInstanceId("host-numa0"));

        fanout.forward(batch());
        assertEquals(0, sends.get(), "nothing can be sent before the producer exists");

        pending.complete(producer);

        assertEquals(1, sends.get(), "the queued batch should have been sent once the producer arrived");
    }

    /**
     * A tenant whose producer the broker refuses must back off, not retry on every batch, and must
     * recover once the broker accepts producers again. The refusal arrives as an already-failed
     * future, so the completion callback runs inline — a failed future left cached there would
     * leave the tenant dead until the consumer restarted.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRefusedProducerBacksOffAndThenRecovers() {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<Object> builder = mock(ProducerBuilder.class, Answers.RETURNS_SELF);
        when(client.newProducer(any(Schema.class))).thenReturn(builder);
        when(builder.createAsync()).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("Cannot create producer on topic with backlog quota exceeded")));

        SubscriptionFanout fanout = new SubscriptionFanout(
                client, topicNames(), cacheWith("sub-a"), new AppInstanceId("host-numa0"));
        fanout.retryBackoffMs = 0;   // don't make the test wait out the real backoff

        fanout.forward(batch());
        fanout.forward(batch());

        Producer<DataWrapperMessage> producer = mock(Producer.class);
        TypedMessageBuilder<DataWrapperMessage> message = mock(TypedMessageBuilder.class, Answers.RETURNS_SELF);
        when(producer.newMessage()).thenReturn(message);
        AtomicInteger sends = new AtomicInteger();
        when(message.sendAsync()).thenAnswer(inv -> {
            sends.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        when(builder.createAsync()).thenReturn((CompletableFuture) CompletableFuture.completedFuture(producer));

        fanout.forward(batch());

        assertEquals(1, sends.get(), "the tenant should have retried and published once accepted");
    }

    /** A tenant with no {@code pulsar.tenant} is reported, not retried on every batch. */
    @Test
    @SuppressWarnings("unchecked")
    void unconfiguredTenantDoesNotStartAProducerBuild() {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<Object> builder = mock(ProducerBuilder.class, Answers.RETURNS_SELF);
        when(client.newProducer(any(Schema.class))).thenReturn(builder);

        TopicNames topicNames = mock(TopicNames.class);
        when(topicNames.getSubscriptionFanoutTopicName(TENANT))
                .thenThrow(new IllegalStateException("Tenant " + TENANT + " has no 'pulsar.tenant' configured"));

        SubscriptionFanout fanout = new SubscriptionFanout(
                client, topicNames, cacheWith("sub-a"), new AppInstanceId("host-numa0"));

        fanout.forward(batch());
        fanout.forward(batch());

        verify(builder, times(0)).createAsync();
    }

    private static TopicNames topicNames() {
        TopicNames topicNames = mock(TopicNames.class);
        when(topicNames.getSubscriptionFanoutTopicName(TENANT))
                .thenReturn("persistent://" + TENANT + "/subscriptions/fanout");
        return topicNames;
    }

    private static SubscriptionCache cacheWith(String externalId) {
        SubscriptionCache cache = mock(SubscriptionCache.class);
        when(cache.getSubscriptionExternalIds(TENANT, 1L)).thenReturn(Set.of(externalId));
        return cache;
    }

    private static DataWrapperMessage batch() {
        DataCollectionString item = new DataCollectionString();
        item.setId(1L);
        item.setExternalId("ts-1");
        return new DataWrapperMessage(EventObject.DATAPOINTS, EventAction.CREATE, List.of(item), TENANT);
    }
}
