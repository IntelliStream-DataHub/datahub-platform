// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;

/**
 * Single-threaded, ordered receive loop for a Pulsar <b>Failover</b> subscription in the stateful
 * consumer. Failover keeps exactly one active consumer, so this pulls one message at a time and
 * applies it on a single dedicated thread, preserving total order.
 *
 * <p><b>Failure handling — retry in place, then skip.</b> A failed message is retried in place
 * (bounded, with a fixed backoff) rather than nacked: a nack lets later messages pass while the
 * failed one waits for redelivery, which reorders the stream and can replay stale state (e.g. a
 * CREATE re-applied after a later UPDATE). Blocking the single loop thread instead keeps the stream
 * head where it is until the mutation succeeds. After {@code maxAttempts} the message is logged and
 * <b>skipped</b> (acked) so one poison message can't pin the head forever — Pulsar's own dead-letter
 * is inert on a single-active subscription. Note: a skipped mutation leaves that entity DIVERGED in
 * the graph. Postgres remains the source of truth, but no automated graph rebuild/reconciliation
 * job exists yet — recovery today means re-applying the entity manually (or a full graph rebuild by
 * hand), so treat every skip ERROR log as actionable. A long systemic outage (every message failing
 * past {@code maxAttempts}) therefore skips messages; size the retry budget to ride out a typical
 * transient outage. Writes are idempotent (MERGE), so a redelivery after a crash/reconnect is safe.
 */
@Slf4j
public final class PulsarReceiveLoop<T> implements AutoCloseable {

    /** Applies one message's payload; throws to signal a retryable failure. */
    @FunctionalInterface
    public interface Handler<T> {
        void handle(T message) throws Exception;
    }

    private final Consumer<T> consumer;
    private final String name;
    private final Handler<T> handler;
    private final int maxAttempts;
    private final long retryBackoffMs;

    private volatile boolean running = true;
    private Thread thread;

    public PulsarReceiveLoop(Consumer<T> consumer, String name, Handler<T> handler,
                             int maxAttempts, long retryBackoffMs) {
        this.consumer = consumer;
        this.name = name;
        this.handler = handler;
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    public void start() {
        thread = new Thread(this::loop, "pulsar-receive-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        log.info("Started receive loop '{}'", name);
        while (running) {
            Message<T> msg;
            try {
                msg = consumer.receive();
            } catch (PulsarClientException.AlreadyClosedException closed) {
                break;
            } catch (PulsarClientException e) {
                if (!running) break;
                log.error("Receive failed on '{}': {}", name, e.getMessage());
                if (!sleep(retryBackoffMs)) break;
                continue;
            }
            process(msg);
        }
        log.info("Stopped receive loop '{}'", name);
    }

    // package-private for testing
    void process(Message<T> msg) {
        for (int attempt = 1; ; attempt++) {
            try {
                handler.handle(msg.getValue());
                ack(msg);
                return;
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    log.error("Skipping message on '{}' after {} attempt(s) (messageId={}): {}",
                            name, attempt, msg.getMessageId(), e.getMessage(), e);
                    ack(msg);
                    return;
                }
                log.warn("Processing failed on '{}' (attempt {}/{}, messageId={}): {} — retrying in {}ms",
                        name, attempt, maxAttempts, msg.getMessageId(), e.getMessage(), retryBackoffMs);
                if (!sleep(retryBackoffMs)) {
                    // Interrupted (shutdown) — leave it unacked so it is redelivered; MERGE makes that safe.
                    return;
                }
            }
        }
    }

    private void ack(Message<T> msg) {
        try {
            consumer.acknowledge(msg);
        } catch (PulsarClientException e) {
            // Ack failed (e.g. mid-reconnect); the message is redelivered and idempotent writes absorb it.
            log.warn("Ack failed on '{}' (messageId={}): {}", name, msg.getMessageId(), e.getMessage());
        }
    }

    private boolean sleep(long ms) {
        if (ms <= 0) return running;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            consumer.close();
        } catch (PulsarClientException e) {
            log.warn("Failed to close consumer for '{}': {}", name, e.getMessage());
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
}
