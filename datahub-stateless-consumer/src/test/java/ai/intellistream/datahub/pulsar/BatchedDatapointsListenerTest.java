// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

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
import static org.mockito.Mockito.when;

class BatchedDatapointsListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void initFailsFastWhenSubscribeFails() throws Exception {
        // A swallowed subscribe failure would leave this headless app "up" but consuming no
        // datapoints — a silent, permanent ingestion stall. init() must abort startup instead.
        PulsarClient client = mock(PulsarClient.class);
        ConsumerBuilder<Object> builder = mock(ConsumerBuilder.class, Answers.RETURNS_SELF);
        when(client.newConsumer(any(Schema.class))).thenReturn(builder);
        when(builder.subscribe()).thenThrow(new PulsarClientException("broker unreachable"));

        TopicNames topicNames = mock(TopicNames.class);
        when(topicNames.getAllDatapointsTopicName())
                .thenReturn("persistent://internal/datapoints/all-datapoints");

        BatchedDatapointsListener listener =
                new BatchedDatapointsListener(client, null, topicNames, null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, listener::init);
        assertTrue(ex.getMessage().contains("all-datapoints consumer"), ex.getMessage());
    }
}
