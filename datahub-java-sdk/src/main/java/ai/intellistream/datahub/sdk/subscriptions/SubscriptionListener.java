// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.subscriptions;

import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A live WebSocket listener over one or more subscriptions. Messages arrive event-driven over the
 * socket and are buffered in memory. Consume them either by registering a callback with
 * {@link #stream(Consumer)} — a dedicated virtual thread delivers each message in real time, acking
 * on normal return — or by draining {@link #poll(Duration)} in a loop and acking with {@link #ack}
 * yourself. Anything left unacked at close is redelivered by the backend on reconnect. Close it when
 * done.
 */
public final class SubscriptionListener implements AutoCloseable {

    private final WebSocket webSocket;
    private final JsonMapper mapper;
    private final BlockingQueue<SubscriptionMessage> queue;
    private final BlockingQueue<SubscriptionError> errors;
    private final Object sendLock = new Object();

    private SubscriptionListener(WebSocket webSocket, JsonMapper mapper,
                                 BlockingQueue<SubscriptionMessage> queue, BlockingQueue<SubscriptionError> errors) {
        this.webSocket = webSocket;
        this.mapper = mapper;
        this.queue = queue;
        this.errors = errors;
    }

    /** Open a listener for the given subscription external ids (handshake authed with the bearer token). */
    public static SubscriptionListener connect(HttpClient httpClient, String baseUrl, String token,
                                               JsonMapper mapper, List<String> externalIds) {
        BlockingQueue<SubscriptionMessage> queue = new LinkedBlockingQueue<>();
        BlockingQueue<SubscriptionError> errors = new LinkedBlockingQueue<>();
        URI uri = buildUri(baseUrl, externalIds);
        WebSocket webSocket;
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token)
                    .buildAsync(uri, new FrameListener(queue, errors, mapper))
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DatahubApiException(0, "subscription WebSocket connect failed: " + cause.getMessage(), null);
        }
        return new SubscriptionListener(webSocket, mapper, queue, errors);
    }

    /** Visible for testing: wrap an injected socket and a pre-seeded message queue. */
    static SubscriptionListener forTesting(WebSocket webSocket, JsonMapper mapper,
                                           BlockingQueue<SubscriptionMessage> queue) {
        return new SubscriptionListener(webSocket, mapper, queue, new LinkedBlockingQueue<>());
    }

    /** Visible for testing: wrap an injected socket with pre-seeded message and error queues. */
    static SubscriptionListener forTesting(WebSocket webSocket, JsonMapper mapper,
                                           BlockingQueue<SubscriptionMessage> queue,
                                           BlockingQueue<SubscriptionError> errors) {
        return new SubscriptionListener(webSocket, mapper, queue, errors);
    }

    /** Wait up to {@code timeout} for the next message; returns {@code null} if none arrived. */
    public SubscriptionMessage poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Wait up to {@code timeout} for the next server error notice (e.g. a subscription refused with
     * {@code "forbidden"} or {@code "not-found"}); returns {@code null} if none arrived. Poll this
     * alongside {@link #poll}/{@link #stream} so a refused subscription surfaces instead of looking
     * like an indefinitely silent stream.
     */
    public SubscriptionError pollError(Duration timeout) throws InterruptedException {
        return errors.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** How {@link #stream} acknowledges each delivered message. */
    public enum AckMode {
        /** Ack after the handler returns normally; nack it if the handler throws. */
        AUTO,
        /** Hands off to the handler, which must call {@link #ack}/{@link #nack} itself. */
        MANUAL
    }

    /**
     * Push messages to {@code handler} on a dedicated virtual thread as they arrive — real-time
     * delivery with no poll loop. Each message is acked once the handler returns, or nacked if it
     * throws (see {@link AckMode#AUTO}). Returns a handle; closing it stops delivery and closes this
     * listener, so use it in a try-with-resources.
     */
    public AutoCloseable stream(Consumer<SubscriptionMessage> handler) {
        return stream(handler, AckMode.AUTO);
    }

    /**
     * As {@link #stream(Consumer)} but with an explicit {@link AckMode}. Under {@link AckMode#MANUAL}
     * the handler owns acknowledgement and must call {@link #ack}/{@link #nack} for each message.
     */
    public AutoCloseable stream(Consumer<SubscriptionMessage> handler, AckMode ackMode) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(ackMode, "ackMode");
        Thread worker = Thread.ofVirtual().name("datahub-subscription-stream").start(() -> {
            try {
                while (true) {
                    SubscriptionMessage message = queue.take(); // blocks until a message arrives
                    try {
                        handler.accept(message);
                        if (ackMode == AckMode.AUTO) {
                            ack(message.messageId());
                        }
                    } catch (RuntimeException handlerError) {
                        if (ackMode == AckMode.AUTO) {
                            try {
                                nack(message.messageId());
                            } catch (RuntimeException ignored) {
                                // best-effort nack; the backend redelivers anything left unacked
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // normal shutdown triggered by close()
            }
        });
        return () -> {
            worker.interrupt();
            close();
        };
    }

    /** Acknowledge processed messages so the backend won't redeliver them. */
    public void ack(String... messageIds) {
        sendText(Map.of("action", "ack", "messageIds", List.of(messageIds)));
    }

    /** Negatively acknowledge messages so the backend redelivers them. */
    public void nack(String... messageIds) {
        sendText(Map.of("action", "nack", "messageIds", List.of(messageIds)));
    }

    /** Add subscriptions to this connection at runtime. */
    public void subscribe(List<String> externalIds) {
        sendText(Map.of("action", "subscribe", "externalIds", externalIds));
    }

    /** Remove subscriptions from this connection. */
    public void unsubscribe(List<String> externalIds) {
        sendText(Map.of("action", "unsubscribe", "externalIds", externalIds));
    }

    @Override
    public void close() {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closing").join();
    }

    private void sendText(Map<String, Object> frame) {
        String json = mapper.writeValueAsString(frame);
        synchronized (sendLock) { // the JDK WebSocket requires sends to be sequential
            webSocket.sendText(json, true).join();
        }
    }

    /**
     * Build the listen URI:
     * {@code ws(s)://<host>/timeseries/datapoints/subscription/listen[/<id>/<id>...]}.
     */
    static URI buildUri(String baseUrl, List<String> externalIds) {
        String scheme = baseUrl.regionMatches(true, 0, "https", 0, 5) ? "wss" : "ws";
        String hostAndPort = baseUrl.replaceFirst("(?i)^https?://", "");
        StringBuilder url = new StringBuilder(scheme).append("://").append(hostAndPort)
                .append("/timeseries/datapoints/subscription/listen");
        for (String id : externalIds) {
            url.append('/').append(id);
        }
        return URI.create(url.toString());
    }

    /** Parse one server frame {@code {subscriptionExternalId, messages:[{messageId, payload}]}} into messages. */
    static List<SubscriptionMessage> parseFrame(String frame, JsonMapper mapper) {
        ServerFrame parsed;
        try {
            parsed = mapper.readValue(frame, ServerFrame.class);
        } catch (RuntimeException e) {
            return List.of(); // ignore unparseable / non-data frames (e.g. error frames)
        }
        if (parsed.messages() == null || parsed.messages().isEmpty()) {
            return List.of();
        }
        List<SubscriptionMessage> messages = new ArrayList<>(parsed.messages().size());
        for (ServerMessage message : parsed.messages()) {
            messages.add(new SubscriptionMessage(parsed.subscriptionExternalId(), message.messageId(), message.payload()));
        }
        return messages;
    }

    /**
     * Parse a server error frame {@code {"error": true, "subscriptionExternalId": "<id>", "reason": "<reason>"}}
     * into a {@link SubscriptionError}, or {@code null} when the frame is not an error frame (a data
     * frame, or unparseable). Only frames with {@code error == true} are treated as errors.
     */
    static SubscriptionError parseError(String frame, JsonMapper mapper) {
        ServerError parsed;
        try {
            parsed = mapper.readValue(frame, ServerError.class);
        } catch (RuntimeException e) {
            return null; // not an error frame (e.g. a data frame whose "error" is absent, or unparseable)
        }
        if (!parsed.error()) {
            return null;
        }
        return new SubscriptionError(parsed.subscriptionExternalId(), parsed.reason());
    }

    /** Accumulates text fragments and enqueues parsed messages (or error notices). */
    static final class FrameListener implements WebSocket.Listener {
        private final BlockingQueue<SubscriptionMessage> queue;
        private final BlockingQueue<SubscriptionError> errors;
        private final JsonMapper mapper;
        private final StringBuilder buffer = new StringBuilder();

        FrameListener(BlockingQueue<SubscriptionMessage> queue, BlockingQueue<SubscriptionError> errors, JsonMapper mapper) {
            this.queue = queue;
            this.errors = errors;
            this.mapper = mapper;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                // An error frame (e.g. a "forbidden" subscription refusal) is routed to the error
                // queue so it surfaces via pollError instead of being silently dropped.
                SubscriptionError error = parseError(frame, mapper);
                if (error != null) {
                    errors.add(error);
                } else {
                    queue.addAll(parseFrame(frame, mapper));
                }
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerFrame(String subscriptionExternalId, List<ServerMessage> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerMessage(String messageId, DataWrapperMessage payload) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerError(boolean error, String subscriptionExternalId, String reason) {
    }
}
