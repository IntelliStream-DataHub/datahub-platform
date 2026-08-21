// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionNotifyListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void initFailsFastWhenSubscribeFails() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ConsumerBuilder<Object> builder = mock(ConsumerBuilder.class, Answers.RETURNS_SELF);
        when(client.newConsumer(any(Schema.class))).thenReturn(builder);
        when(builder.subscribe()).thenThrow(new PulsarClientException("broker unreachable"));

        TopicNames topicNames = mock(TopicNames.class);
        when(topicNames.getSubscriptionNotifyTopicName())
                .thenReturn("persistent://internal/subscriptions/notify");
        SubscriptionCache cache = mock(SubscriptionCache.class);

        SubscriptionNotifyListener listener = new SubscriptionNotifyListener(
                client, topicNames, cache, new AppInstanceId("host-numa0"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, listener::init);
        assertTrue(ex.getMessage().contains("Failed to subscribe"), ex.getMessage());
        // A failed subscribe must NOT proceed to load the snapshot (it would run half-working).
        verify(cache, never()).loadAll();
    }
}
