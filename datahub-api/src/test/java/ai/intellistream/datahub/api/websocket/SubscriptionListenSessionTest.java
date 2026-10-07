// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every subscription of a tenant reads the same fan-out topic, and the broker-side entry filter is
 * what normally keeps one subscription's messages away from another's consumers. These tests pin the
 * second line of defence in {@link SubscriptionListenSession}: with no filter in front of it, a
 * consumer is handed the whole topic, and only the messages keyed with its own subscription's
 * externalId may reach the socket. The rest are acknowledged so they do not build up a backlog.
 */
class SubscriptionListenSessionTest {

    private static final String OWN = "sub-a";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> frames = new CopyOnWriteArrayList<>();
    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doAnswer(inv -> frames.add(((TextMessage) inv.getArgument(0)).getPayload()))
                .when(session).sendMessage(any());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("only messages keyed with the stream's own externalId reach the socket")
    void forwardsOnlyItsOwnMessages() throws Exception {
        Message<DataWrapperMessage> own = message(OWN, 1);
        Message<DataWrapperMessage> otherSubscription = message("sub-b", 2);
        Message<DataWrapperMessage> keyless = message(null, 3);
        Consumer<DataWrapperMessage> consumer = consumerDelivering(own, otherSubscription, keyless);

        receiveOneBatch(consumer);

        assertThat(frames).hasSize(1);
        JsonNode frame = jsonMapper.readTree(frames.getFirst());
        assertThat(frame.get("subscriptionExternalId").asString()).isEqualTo(OWN);
        assertThat(frame.get("messages")).hasSize(1);
        verify(consumer).acknowledge(List.of(otherSubscription.getMessageId(), keyless.getMessageId()));
        verify(consumer, never()).acknowledge(own.getMessageId());
    }

    @Test
    @DisplayName("a batch with nothing for this subscription sends no frame and is acknowledged")
    void dropsAForeignBatchWithoutSending() throws Exception {
        Message<DataWrapperMessage> first = message("sub-b", 1);
        Message<DataWrapperMessage> second = message("sub-c", 2);
        Consumer<DataWrapperMessage> consumer = consumerDelivering(first, second);

        receiveOneBatch(consumer);

        verify(session, never()).sendMessage(any());
        verify(consumer).acknowledge(List.of(first.getMessageId(), second.getMessageId()));
    }

    @Test
    @DisplayName("a batch that is all its own is forwarded whole, with nothing acknowledged early")
    void forwardsAnOwnBatchUntouched() throws Exception {
        Consumer<DataWrapperMessage> consumer = consumerDelivering(message(OWN, 1), message(OWN, 2));

        receiveOneBatch(consumer);

        assertThat(frames).hasSize(1);
        assertThat(jsonMapper.readTree(frames.getFirst()).get("messages")).hasSize(2);
        verify(consumer, never()).acknowledge(anyList());
    }

    @Test
    @DisplayName("a client that attached in other case still gets the messages keyed with the stored id")
    void matchesTheStoredIdNotTheClientsCasing() throws Exception {
        Consumer<DataWrapperMessage> consumer = consumerDelivering(message(OWN, 1));

        receiveOneBatch("SUB-A", consumer);

        assertThat(frames).hasSize(1);
        JsonNode frame = jsonMapper.readTree(frames.getFirst());
        assertThat(frame.get("subscriptionExternalId").asString()).isEqualTo("SUB-A");
        assertThat(frame.get("messages")).hasSize(1);
        verify(consumer, never()).acknowledge(anyList());
    }

    private void receiveOneBatch(Consumer<DataWrapperMessage> consumer) throws InterruptedException {
        receiveOneBatch(OWN, consumer);
    }

    /**
     * Run one receive loop for one batch; the consumer reports closed on the next call. The stream is
     * attached as {@code attachedAs}, the way the client spelled it, over the stored id {@link #OWN}.
     */
    private void receiveOneBatch(String attachedAs, Consumer<DataWrapperMessage> consumer)
            throws InterruptedException {
        SubscriptionListenSession listen = new SubscriptionListenSession(session, jsonMapper, executor);
        assertThat(listen.addStream(attachedAs, OWN, consumer)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static Consumer<DataWrapperMessage> consumerDelivering(Message<DataWrapperMessage>... messages)
            throws PulsarClientException {
        List<Message<DataWrapperMessage>> list = List.of(messages);
        Messages<DataWrapperMessage> batch = mock(Messages.class);
        when(batch.size()).thenReturn(list.size());
        when(batch.iterator()).thenAnswer(inv -> list.iterator());
        Consumer<DataWrapperMessage> consumer = mock(Consumer.class);
        when(consumer.batchReceive())
                .thenReturn(batch)
                .thenThrow(new PulsarClientException.AlreadyClosedException("closed"));
        return consumer;
    }

    @SuppressWarnings("unchecked")
    private static Message<DataWrapperMessage> message(String key, int id) {
        MessageId messageId = mock(MessageId.class);
        when(messageId.toByteArray()).thenReturn(new byte[]{(byte) id});
        Message<DataWrapperMessage> message = mock(Message.class);
        when(message.getKey()).thenReturn(key);
        when(message.hasKey()).thenReturn(key != null);
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getValue()).thenReturn(
                new DataWrapperMessage(EventObject.DATAPOINTS, EventAction.CREATE, List.of(), "tenant"));
        return message;
    }
}
