// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.pulsar.TopicNames;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceLockTest {

    private static final String LOCK_TOPIC = "persistent://internal/subscriptions/instance-lock";

    @SuppressWarnings("unchecked")
    private static ProducerBuilder<byte[]> stubbedBuilder(PulsarClient client) throws Exception {
        // RETURNS_SELF lets the fluent chain (topic/producerName/accessMode) return the same mock.
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class, Answers.RETURNS_SELF);
        when(client.newProducer()).thenReturn(builder);
        return builder;
    }

    private static TopicNames topicNames() {
        TopicNames names = mock(TopicNames.class);
        when(names.getInstanceLockTopicName()).thenReturn(LOCK_TOPIC);
        return names;
    }

    @Test
    void failsFastWithAClearMessageWhenTheIdIsAlreadyInUse() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = stubbedBuilder(client);
        when(builder.create()).thenThrow(new PulsarClientException.ProducerBusyException(
                "Producer with name 'datahub-api-host-numa0' is already connected to topic"));

        InstanceLock lock = new InstanceLock(client, topicNames(), new AppInstanceId("host-numa0"), "datahub-api");

        IllegalStateException ex = assertThrows(IllegalStateException.class, lock::acquire);
        assertTrue(ex.getMessage().contains("already in use"), ex.getMessage());
        assertTrue(ex.getMessage().contains("app.id"), ex.getMessage());
        assertTrue(ex.getMessage().contains("host-numa0"), ex.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acquiresWhenTheIdIsFree() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = stubbedBuilder(client);
        when(builder.create()).thenReturn(mock(Producer.class));

        InstanceLock lock = new InstanceLock(client, topicNames(), new AppInstanceId("host-numa0"), "datahub-api");

        assertDoesNotThrow(lock::acquire);
        assertEquals("host-numa0", lock.id());
    }
}
