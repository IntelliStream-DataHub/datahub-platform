// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PulsarReceiveLoopTest {

    @SuppressWarnings("unchecked")
    private static Message<String> message(String value) {
        Message<String> msg = mock(Message.class);
        when(msg.getValue()).thenReturn(value);
        when(msg.getMessageId()).thenReturn(mock(MessageId.class));
        return msg;
    }

    @Test
    @SuppressWarnings("unchecked")
    void acksAfterSuccessfulHandling() throws Exception {
        Consumer<String> consumer = mock(Consumer.class);
        AtomicInteger calls = new AtomicInteger();
        PulsarReceiveLoop<String> loop = new PulsarReceiveLoop<>(
                consumer, "t", v -> calls.incrementAndGet(), 3, 0);

        Message<String> msg = message("x");
        loop.process(msg);

        assertEquals(1, calls.get());
        verify(consumer, times(1)).acknowledge(msg);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesInPlaceThenAcksOnEventualSuccess() throws Exception {
        Consumer<String> consumer = mock(Consumer.class);
        AtomicInteger calls = new AtomicInteger();
        // Fail twice, succeed on the third attempt.
        PulsarReceiveLoop<String> loop = new PulsarReceiveLoop<>(consumer, "t",
                v -> { if (calls.incrementAndGet() < 3) throw new RuntimeException("transient"); }, 5, 0);

        Message<String> msg = message("x");
        loop.process(msg);

        assertEquals(3, calls.get());
        verify(consumer, times(1)).acknowledge(msg);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsPoisonMessageAfterMaxAttempts() throws Exception {
        Consumer<String> consumer = mock(Consumer.class);
        AtomicInteger calls = new AtomicInteger();
        // Always fails: after maxAttempts the loop skips (acks) so it can't pin the ordered head.
        PulsarReceiveLoop<String> loop = new PulsarReceiveLoop<>(consumer, "t",
                v -> { calls.incrementAndGet(); throw new RuntimeException("poison"); }, 3, 0);

        Message<String> msg = message("x");
        loop.process(msg);

        assertEquals(3, calls.get());               // tried exactly maxAttempts times
        verify(consumer, times(1)).acknowledge(msg); // then skipped (acked)
    }
}
