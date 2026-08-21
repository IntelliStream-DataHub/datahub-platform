// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-connection state for the subscription listen WebSocket. A single socket can multiplex
 * several subscriptions at once: each gets its own Pulsar consumer and receive loop, and the
 * client adds or removes them at runtime (see {@link SubscriptionWebSocketHandler}). Every
 * server→client frame carries the owning subscription's {@code externalId} so the client can tell
 * the streams apart, and the opaque {@code messageId} the client acks with encodes that externalId
 * so the ack is routed back to the consumer it came from.
 */
@Slf4j
class SubscriptionListenSession {

    private final WebSocketSession session;
    private final JsonMapper jsonMapper;
    private final ExecutorService executor;

    // externalId -> its consumer + receive loop.
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    // wsMessageId -> the subscription + Pulsar id it belongs to, awaiting client ack/nack.
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private record Pending(String externalId, MessageId messageId) {}

    private static final class Stream {
        final String externalId;
        final Consumer<DataWrapperMessage> consumer;
        volatile Future<?> task;

        Stream(String externalId, Consumer<DataWrapperMessage> consumer) {
            this.externalId = externalId;
            this.consumer = consumer;
        }
    }

    SubscriptionListenSession(WebSocketSession session, JsonMapper jsonMapper, ExecutorService executor) {
        this.session = session;
        this.jsonMapper = jsonMapper;
        this.executor = executor;
    }

    /** The subscription externalIds currently streamed on this connection. */
    Set<String> subscribedExternalIds() {
        return new HashSet<>(streams.keySet());
    }

    boolean hasStream(String externalId) {
        return streams.containsKey(externalId);
    }

    /**
     * Attach a subscription's consumer and start streaming it. Returns false (and does NOT take
     * ownership of the consumer) if the connection is stopping or the subscription is already
     * attached — the caller must close the redundant consumer in that case.
     */
    boolean addStream(String externalId, Consumer<DataWrapperMessage> consumer) {
        if (!running.get()) return false;
        Stream stream = new Stream(externalId, consumer);
        if (streams.putIfAbsent(externalId, stream) != null) return false;
        stream.task = executor.submit(() -> receiveLoop(stream));
        return true;
    }

    /** Detach a subscription: close its consumer, cancel its loop, and drop its pending acks. */
    void removeStream(String externalId) {
        Stream stream = streams.remove(externalId);
        if (stream == null) return;
        closeStream(stream);
        pending.entrySet().removeIf(e -> e.getValue().externalId().equals(externalId));
    }

    /** Tear the whole connection down. Idempotent. */
    void stop() {
        if (!running.compareAndSet(true, false)) return;
        streams.values().forEach(this::closeStream);
        streams.clear();
        pending.clear();
    }

    private void closeStream(Stream stream) {
        try {
            stream.consumer.close();
        } catch (PulsarClientException e) {
            log.warn("Failed to close Pulsar consumer for subscription {}: {}", stream.externalId, e.getMessage());
        }
        if (stream.task != null) stream.task.cancel(true);
    }

    /** Pulsar-acknowledge the messages with the supplied (subscription-scoped) ws message ids. */
    void acknowledge(Collection<String> wsMessageIds) {
        for (String id : wsMessageIds) {
            Pending p = pending.remove(id);
            if (p == null) continue;
            Stream stream = streams.get(p.externalId());
            if (stream == null) continue;
            try {
                stream.consumer.acknowledge(p.messageId());
            } catch (PulsarClientException e) {
                log.error("Failed to ack Pulsar message for subscription {}: {}", p.externalId(), e.getMessage());
            }
        }
    }

    /** Negative-acknowledge so Pulsar redelivers, routed to the owning consumer. */
    void negativeAcknowledge(Collection<String> wsMessageIds) {
        for (String id : wsMessageIds) {
            Pending p = pending.remove(id);
            if (p == null) continue;
            Stream stream = streams.get(p.externalId());
            if (stream == null) continue;
            stream.consumer.negativeAcknowledge(p.messageId());
        }
    }

    private void receiveLoop(Stream stream) {
        Thread.currentThread().setName("subscription-listen-" + stream.externalId);
        log.info("Starting receive loop for subscription {}", stream.externalId);
        try {
            while (running.get() && session.isOpen() && streams.containsKey(stream.externalId)) {
                Messages<DataWrapperMessage> batch;
                try {
                    batch = stream.consumer.batchReceive();
                } catch (PulsarClientException.AlreadyClosedException closed) {
                    log.debug("Pulsar consumer for {} already closed, exiting loop", stream.externalId);
                    return;
                } catch (PulsarClientException e) {
                    log.error("Pulsar receive failed for {}: {}", stream.externalId, e.getMessage());
                    return;
                }
                if (batch == null || batch.size() == 0) continue;
                sendBatch(stream, batch);
            }
        } catch (Exception e) {
            log.error("Unexpected error in receive loop for {}: {}", stream.externalId, e.getMessage(), e);
        } finally {
            log.info("Exiting receive loop for subscription {}", stream.externalId);
        }
    }

    /**
     * Serialize one consumer's batch into a single WS frame tagged with its subscription externalId,
     * registering each message so the client can later ack/nack by wsMessageId. Concurrent sends from
     * sibling streams are made safe by the {@code ConcurrentWebSocketSessionDecorator} wrapping the
     * session.
     */
    private void sendBatch(Stream stream, Messages<DataWrapperMessage> batch) {
        List<Map<String, Object>> wireMessages = new ArrayList<>(batch.size());
        for (Message<DataWrapperMessage> msg : batch) {
            String wsMessageId = encodeMessageId(stream.externalId, msg.getMessageId());
            pending.put(wsMessageId, new Pending(stream.externalId, msg.getMessageId()));
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("messageId", wsMessageId);
            envelope.put("payload", msg.getValue());
            wireMessages.add(envelope);
        }
        try {
            String json = jsonMapper.writeValueAsString(Map.of(
                    "subscriptionExternalId", stream.externalId,
                    "messages", wireMessages));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("Failed to send batch for subscription {}: {}", stream.externalId, e.getMessage());
            // Send failure means the socket is broken — nack this batch so Pulsar redelivers on the
            // next consumer, then close the whole connection (sibling streams can't survive it either).
            for (Map<String, Object> env : wireMessages) {
                Pending p = pending.remove((String) env.get("messageId"));
                if (p != null) stream.consumer.negativeAcknowledge(p.messageId());
            }
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    /**
     * Encode {@code <base64url(externalId)>.<base64url(pulsarMessageId)>} so the ws message id is
     * unique across the connection's subscriptions and the ack can be routed to the right consumer.
     */
    private static String encodeMessageId(String externalId, MessageId id) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(externalId.getBytes(StandardCharsets.UTF_8))
                + "." + enc.encodeToString(id.toByteArray());
    }
}
