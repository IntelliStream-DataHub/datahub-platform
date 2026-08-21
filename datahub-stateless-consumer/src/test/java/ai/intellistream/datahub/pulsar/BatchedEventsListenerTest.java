// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchedEventsListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void initFailsFastWhenSubscribeFails() throws Exception {
        // A swallowed subscribe failure would leave this headless app "up" but consuming no events —
        // a silent, permanent ingestion stall. init() must abort startup instead.
        PulsarClient client = mock(PulsarClient.class);
        ConsumerBuilder<Object> builder = mock(ConsumerBuilder.class, Answers.RETURNS_SELF);
        when(client.newConsumer(any(Schema.class))).thenReturn(builder);
        when(builder.subscribe()).thenThrow(new PulsarClientException("broker unreachable"));

        TopicNames topicNames = mock(TopicNames.class);
        when(topicNames.getEventsTopicName())
                .thenReturn("persistent://internal/events/cud-events");

        BatchedEventsListener listener = new BatchedEventsListener(client, null, null, topicNames);

        IllegalStateException ex = assertThrows(IllegalStateException.class, listener::init);
        assertTrue(ex.getMessage().contains("events consumer"), ex.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchedUpdatesKeepEveryMessagesResolvedEvents() {
        // The related-resource mutation derives its three ClickHouse columns from the RESOLVED
        // EventModels that travel alongside the patch forms. When several UPDATE messages for one
        // tenant are batched, the first becomes the accumulator — so its list must absorb the
        // others' events too, or every message after the first loses its related resources.
        ClickHouseEventService clickHouse = mock(ClickHouseEventService.class);
        EventDimensionRepository dimensions = mock(EventDimensionRepository.class);
        BatchedEventsListener listener =
                new BatchedEventsListener(mock(PulsarClient.class), clickHouse, dimensions, mock(TopicNames.class));

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        List<Message<EventCudMessage>> batch = List.of(
                updateMessage("tenant-a", firstId), updateMessage("tenant-a", secondId));

        org.apache.pulsar.client.api.Consumer<EventCudMessage> consumer = mock(org.apache.pulsar.client.api.Consumer.class);
        when(consumer.acknowledgeAsync(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));
        listener.processBatchForTest(batch, consumer);

        ArgumentCaptor<EventCudMessage> captor = ArgumentCaptor.forClass(EventCudMessage.class);
        verify(clickHouse).updateEvents(captor.capture());
        EventCudMessage merged = captor.getValue();

        assertEquals(2, merged.getUpdateEvents().size(), "both patch forms must survive the merge");
        assertEquals(Set.of(firstId.toString(), secondId.toString()),
                merged.getEvents().stream().map(EventModel::getId).collect(Collectors.toSet()),
                "both resolved models must survive the merge");
    }

    @SuppressWarnings("unchecked")
    private static Message<EventCudMessage> updateMessage(String tenantId, UUID eventId) {
        EventModel resolved = new EventModel();
        resolved.setId(eventId.toString());
        resolved.setExternalId("ext-" + eventId);

        EventCudMessage payload = new EventCudMessage();
        payload.setTenantId(tenantId);
        payload.setEventObject(EventObject.EVENT);
        payload.setEventAction(EventAction.UPDATE);
        payload.setEvents(new ArrayList<>(List.of(resolved)));
        payload.setUpdateEvents(new ArrayList<>(List.of(new UpdateEventForm().setId(eventId))));

        Message<EventCudMessage> message = mock(Message.class);
        when(message.getValue()).thenReturn(payload);
        return message;
    }
}
