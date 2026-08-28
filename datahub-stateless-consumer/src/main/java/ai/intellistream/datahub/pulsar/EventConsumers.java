// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Consumes event-CUD messages and applies external-id renames / deletes to KVRocks.
 *
 * <p>Lives in the stateless consumer, which scales out, but the subscription below is Failover:
 * the broker keeps exactly one instance active and the rest as standbys, so the renames stay
 * ordered however many instances run. Ordering is not optional here — a rename applied after a
 * later one leaves the key index pointing at a name the event no longer has.
 */
@Component
@Profile({"dev", "prod"})
public class EventConsumers {

    private final PulsarClient pulsarClient;
    private final EventKvRocksListener listener;
    private final TopicNames topicNames;
    private final int maxAttempts;
    private final long retryBackoffMs;

    public EventConsumers(
            PulsarClient pulsarClient,
            EventKvRocksListener listener,
            TopicNames topicNames,
            @Value("${datahub.event-kvrocks.retry.max-attempts:10}") int maxAttempts,
            @Value("${datahub.event-kvrocks.retry.backoff-ms:5000}") long retryBackoffMs
    ) {
        this.pulsarClient = pulsarClient;
        this.listener = listener;
        this.topicNames = topicNames;
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Bean
    public PulsarReceiveLoop<EventCudMessage> eventKvRocksReceiveLoop() throws PulsarClientException {
        Schema<EventCudMessage> schema = Schema.AVRO(SchemaDefinition.<EventCudMessage>builder()
                .withJSR310ConversionEnabled(false)
                .withPojo(EventCudMessage.class)
                .build());
        // Failover (not Exclusive): a standby can attach and take over. The receive loop retries a
        // failed message in place then skips, so no DeadLetterPolicy (inert on single-active) is needed.
        Consumer<EventCudMessage> consumer = pulsarClient.newConsumer(schema)
                .subscriptionName("event-cud-sub-kvrocks")
                // A NEW subscription defaults to Latest, so events published before this consumer
                // first attaches would be dropped silently. Existing subscriptions keep their
                // cursor; this only changes where a freshly created one starts.
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .topic(topicNames.getEventsTopicName())
                .subscriptionType(SubscriptionType.Failover)
                .subscribe();
        PulsarReceiveLoop<EventCudMessage> loop = new PulsarReceiveLoop<>(
                consumer, "event-cud-kvrocks", listener::process, maxAttempts, retryBackoffMs);
        loop.start();
        return loop;
    }
}
