// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Consumes resource-CUD messages and applies them to the Neo4j knowledge graph.
 */
@Component
public class GraphEventNeo4jConsumer {

    private final PulsarClient pulsarClient;
    private final GraphEventNeo4jListener listener;
    private final TopicNames topicNames;
    private final int maxAttempts;
    private final long retryBackoffMs;

    public GraphEventNeo4jConsumer(
            PulsarClient pulsarClient,
            GraphEventNeo4jListener listener,
            TopicNames topicNames,
            @Value("${datahub.graph.retry.max-attempts:10}") int maxAttempts,
            @Value("${datahub.graph.retry.backoff-ms:5000}") long retryBackoffMs) {
        this.pulsarClient = pulsarClient;
        this.listener = listener;
        this.topicNames = topicNames;
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Bean
    public PulsarReceiveLoop<ResourceCudMessage> assetEventActionNeo4jReceiveLoop() throws PulsarClientException {
        // Failover (not Exclusive): a standby instance can attach and take over on failure, so the
        // order-sensitive graph pipeline is no longer a single point of failure; single-active still
        // gives total ordering. No messageListener/DeadLetterPolicy — the receive loop retries in
        // place then skips, and Pulsar's own DLQ is inert on a single-active subscription anyway.
        Consumer<ResourceCudMessage> consumer = pulsarClient.newConsumer(Schema.AVRO(ResourceCudMessage.class))
                .subscriptionName("graph-cud-sub-neo4j")
                .topic(topicNames.getResourceTopicName())
                .subscriptionType(SubscriptionType.Failover)
                // Earliest, because this position is what a NEW subscription starts from and the
                // default is Latest — so on a first start the api can publish resource CUD before
                // this consumer has attached, and Pulsar drops those messages with no backlog and
                // no error. Measured on a fresh stack: 21 published, 5 delivered, leaving a graph
                // with 52 nodes where 164 belonged, while every REST read looked correct.
                // Only affects subscription creation; an existing subscription resumes from its
                // committed cursor, so this cannot rewind a running deployment. Re-applying a
                // replayed message is safe here: the Cypher is MERGE-based and idempotent.
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();
        PulsarReceiveLoop<ResourceCudMessage> loop = new PulsarReceiveLoop<>(
                consumer, "graph-cud-neo4j", listener::process, maxAttempts, retryBackoffMs);
        loop.start();
        return loop;
    }
}
