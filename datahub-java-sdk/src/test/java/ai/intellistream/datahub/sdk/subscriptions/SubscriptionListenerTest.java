// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.subscriptions;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SubscriptionListenerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void buildUriUpgradesHttpToWsAndAppendsIds() {
        URI uri = SubscriptionListener.buildUri("http://localhost:8081", List.of("alpha", "beta"));
        assertEquals("ws://localhost:8081/timeseries/datapoints/subscription/listen/alpha/beta", uri.toString());
    }

    @Test
    void buildUriUpgradesHttpsToWssWithNoIds() {
        URI uri = SubscriptionListener.buildUri("https://api.example.com", List.of());
        assertEquals("wss://api.example.com/timeseries/datapoints/subscription/listen", uri.toString());
    }

    @Test
    void buildUriSchemeMatchIsCaseInsensitive() {
        URI uri = SubscriptionListener.buildUri("HTTPS://api.example.com", List.of("x"));
        assertEquals("wss://api.example.com/timeseries/datapoints/subscription/listen/x", uri.toString());
    }

    @Test
    void parseFrameExtractsEachMessage() {
        String frame = """
                {
                  "subscriptionExternalId": "sub-1",
                  "messages": [
                    { "messageId": "m1", "payload": {} },
                    { "messageId": "m2", "payload": {} }
                  ]
                }""";

        List<SubscriptionMessage> messages = SubscriptionListener.parseFrame(frame, mapper);

        assertEquals(2, messages.size());
        assertEquals("sub-1", messages.get(0).subscriptionExternalId());
        assertEquals("m1", messages.get(0).messageId());
        assertNotNull(messages.get(0).payload());
        assertEquals("sub-1", messages.get(1).subscriptionExternalId());
        assertEquals("m2", messages.get(1).messageId());
    }

    @Test
    void parseFrameReturnsEmptyForNoMessages() {
        String frame = "{ \"subscriptionExternalId\": \"sub-1\", \"messages\": [] }";
        assertTrue(SubscriptionListener.parseFrame(frame, mapper).isEmpty());
    }

    @Test
    void parseFrameIgnoresUnparseableFrames() {
        assertTrue(SubscriptionListener.parseFrame("not json", mapper).isEmpty());
        assertTrue(SubscriptionListener.parseFrame("{ \"error\": \"boom\" }", mapper).isEmpty());
    }

    @Test
    void parseErrorExtractsForbiddenRefusal() {
        String frame = "{ \"error\": true, \"subscriptionExternalId\": \"sub-secret\", \"reason\": \"forbidden\" }";
        SubscriptionError error = SubscriptionListener.parseError(frame, mapper);
        assertNotNull(error);
        assertEquals("sub-secret", error.subscriptionExternalId());
        assertEquals("forbidden", error.reason());
    }

    @Test
    void parseErrorReturnsNullForDataAndNonErrorFrames() {
        // A data frame has no error flag, so it is not an error.
        assertNull(SubscriptionListener.parseError(
                "{ \"subscriptionExternalId\": \"sub-1\", \"messages\": [] }", mapper));
        // A frame whose "error" is not a boolean true is not treated as an error either.
        assertNull(SubscriptionListener.parseError("{ \"error\": \"boom\" }", mapper));
        assertNull(SubscriptionListener.parseError("not json", mapper));
    }

    @Test
    void forbiddenFrameIsSurfacedAsAnErrorNotAMessage() {
        BlockingQueue<SubscriptionMessage> messages = new LinkedBlockingQueue<>();
        BlockingQueue<SubscriptionError> errors = new LinkedBlockingQueue<>();
        SubscriptionListener.FrameListener frameListener =
                new SubscriptionListener.FrameListener(messages, errors, mapper);

        frameListener.onText(null,
                "{ \"error\": true, \"subscriptionExternalId\": \"sub-denied\", \"reason\": \"forbidden\" }", true);

        assertEquals(1, errors.size());
        SubscriptionError error = errors.poll();
        assertNotNull(error);
        assertEquals("sub-denied", error.subscriptionExternalId());
        assertEquals("forbidden", error.reason());
        assertTrue(messages.isEmpty(), "an error frame must not be delivered as a data message");
    }

    @Test
    void pollErrorReturnsQueuedError() throws Exception {
        BlockingQueue<SubscriptionMessage> messages = new LinkedBlockingQueue<>();
        BlockingQueue<SubscriptionError> errors = new LinkedBlockingQueue<>();
        errors.add(new SubscriptionError("sub-denied", "forbidden"));
        SubscriptionListener listener =
                SubscriptionListener.forTesting(fakeWebSocket(new CopyOnWriteArrayList<>()), mapper, messages, errors);

        SubscriptionError error = listener.pollError(Duration.ofSeconds(1));

        assertNotNull(error);
        assertEquals("forbidden", error.reason());
    }

    @Test
    void dataFrameIsStillDeliveredAsMessagesNotErrors() {
        BlockingQueue<SubscriptionMessage> messages = new LinkedBlockingQueue<>();
        BlockingQueue<SubscriptionError> errors = new LinkedBlockingQueue<>();
        SubscriptionListener.FrameListener frameListener =
                new SubscriptionListener.FrameListener(messages, errors, mapper);

        frameListener.onText(null,
                "{ \"subscriptionExternalId\": \"sub-1\", \"messages\": [ { \"messageId\": \"m1\", \"payload\": {} } ] }",
                true);

        assertEquals(1, messages.size());
        assertTrue(errors.isEmpty());
    }

    @Test
    void streamDeliversMessagesAndAutoAcksAfterHandlerReturns() throws Exception {
        BlockingQueue<SubscriptionMessage> queue = new LinkedBlockingQueue<>();
        List<String> sent = new CopyOnWriteArrayList<>();
        SubscriptionListener listener = SubscriptionListener.forTesting(fakeWebSocket(sent), mapper, queue);

        List<SubscriptionMessage> handled = new CopyOnWriteArrayList<>();
        try (AutoCloseable stream = listener.stream(handled::add)) {
            queue.add(new SubscriptionMessage("sub-1", "m1", null));
            waitUntil(() -> !handled.isEmpty() && acked(sent, "m1"));
        }

        assertEquals(1, handled.size());
        assertEquals("m1", handled.get(0).messageId());
        assertTrue(acked(sent, "m1"));
    }

    @Test
    void streamNacksWhenHandlerThrows() throws Exception {
        BlockingQueue<SubscriptionMessage> queue = new LinkedBlockingQueue<>();
        List<String> sent = new CopyOnWriteArrayList<>();
        SubscriptionListener listener = SubscriptionListener.forTesting(fakeWebSocket(sent), mapper, queue);

        try (AutoCloseable stream = listener.stream(msg -> {
            throw new RuntimeException("boom");
        })) {
            queue.add(new SubscriptionMessage("sub-1", "m2", null));
            waitUntil(() -> nacked(sent, "m2"));
        }

        assertTrue(nacked(sent, "m2"));
    }

    @Test
    void streamManualModeDoesNotAutoAck() throws Exception {
        BlockingQueue<SubscriptionMessage> queue = new LinkedBlockingQueue<>();
        List<String> sent = new CopyOnWriteArrayList<>();
        SubscriptionListener listener = SubscriptionListener.forTesting(fakeWebSocket(sent), mapper, queue);

        CountDownLatch handled = new CountDownLatch(1);
        try (AutoCloseable stream = listener.stream(msg -> handled.countDown(), SubscriptionListener.AckMode.MANUAL)) {
            queue.add(new SubscriptionMessage("sub-1", "m3", null));
            assertTrue(handled.await(2, TimeUnit.SECONDS));
            Thread.sleep(100); // give any erroneous auto-ack a chance to be sent
        }

        assertTrue(sent.stream().noneMatch(frame -> frame.contains("m3")), "manual mode must not ack/nack");
    }

    private static boolean acked(List<String> sent, String messageId) {
        return sent.stream().anyMatch(frame -> frame.contains("\"ack\"") && frame.contains(messageId));
    }

    private static boolean nacked(List<String> sent, String messageId) {
        return sent.stream().anyMatch(frame -> frame.contains("\"nack\"") && frame.contains(messageId));
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("condition not met within timeout");
    }

    /** Minimal in-memory WebSocket that records the text frames the listener sends (acks/nacks). */
    private static WebSocket fakeWebSocket(List<String> sentText) {
        return new WebSocket() {
            @Override
            public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
                sentText.add(data.toString());
                return CompletableFuture.completedFuture(this);
            }

            @Override
            public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
                return CompletableFuture.completedFuture(this);
            }

            @Override
            public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
                return CompletableFuture.completedFuture(this);
            }

            @Override
            public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
                return CompletableFuture.completedFuture(this);
            }

            @Override
            public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
                return CompletableFuture.completedFuture(this);
            }

            @Override
            public void request(long n) {
            }

            @Override
            public String getSubprotocol() {
                return "";
            }

            @Override
            public boolean isOutputClosed() {
                return false;
            }

            @Override
            public boolean isInputClosed() {
                return false;
            }

            @Override
            public void abort() {
            }
        };
    }
}
