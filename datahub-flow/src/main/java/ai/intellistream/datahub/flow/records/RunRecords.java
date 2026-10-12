// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.flow.engine.FlowRecord;
import ai.intellistream.datahub.flow.engine.RecordSink;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * One run attempt's records on their way to the tenant's records topic.
 *
 * <p>Records are collected and published in batches — every {@value #BATCH_SIZE} records, or when
 * {@value #FLUSH_SECONDS} seconds have passed since the last batch went out, and a last batch at
 * {@link #finish}. The time is checked as records arrive, which is when the engine commits an
 * invocation: a processor busy for a minute publishes nothing meanwhile, and has nothing to.
 *
 * <p><b>The cap.</b> An attempt keeps at most {@code cap} records. After that, records are dropped
 * and the attempt's last batch ends with a marker row ({@link FlowRecordBatch.Row#truncatedMarker}),
 * so the record says it is incomplete rather than looking complete.
 *
 * <p><b>Finishing.</b> {@link #finish} publishes the last batch and waits until Pulsar has
 * acknowledged every batch. Only then are the records durable, and a run is never finished with
 * records still only in memory: if the wait fails or times out, {@link #finish} throws and the run
 * fails.
 *
 * <p>Called from the run's own thread only (the engine records on it), so it needs no locking.
 */
public final class RunRecords implements RecordSink {

    static final int BATCH_SIZE = 1_000;
    static final int FLUSH_SECONDS = 5;

    /** Opens the records of one run attempt; the service's is {@link RecordsPublisher}. */
    @FunctionalInterface
    public interface Opener {
        RunRecords open(String tenantId, UUID runId, int attempt, String flowExternalId, int flowVersion);
    }

    /** The records could not be made durable; the run that produced them fails. */
    public static final class UnpublishedException extends Exception {
        UnpublishedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final UUID runId;
    private final int attempt;
    private final String flowExternalId;
    private final int flowVersion;
    private final long cap;
    private final Clock clock;
    private final Function<FlowRecordBatch, CompletableFuture<?>> send;

    private final List<FlowRecordBatch.Row> pending = new ArrayList<>();
    private final List<CompletableFuture<?>> sent = new ArrayList<>();
    private int sequence;
    private long kept;
    private boolean truncated;
    private long lastFlush;

    /**
     * @param send publishes one batch; the future completes when Pulsar has acknowledged it
     */
    public RunRecords(UUID runId, int attempt, String flowExternalId, int flowVersion, long cap,
                      Clock clock, Function<FlowRecordBatch, CompletableFuture<?>> send) {
        this.runId = runId;
        this.attempt = attempt;
        this.flowExternalId = flowExternalId;
        this.flowVersion = flowVersion;
        this.cap = cap;
        this.clock = clock;
        this.send = send;
        this.lastFlush = clock.millis();
    }

    @Override
    public void accept(FlowRecord record) {
        if (kept >= cap) {
            truncated = true;
            return;
        }
        kept++;
        pending.add(FlowRecordBatch.Row.of(record));
        if (pending.size() >= BATCH_SIZE || clock.millis() - lastFlush >= FLUSH_SECONDS * 1000L) {
            flush();
        }
    }

    /** Whether records were dropped for going over the cap. */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Publishes the last batch and waits up to {@code wait} for Pulsar to acknowledge every batch
     * of the attempt.
     */
    public void finish(Duration wait) throws UnpublishedException {
        if (truncated) {
            pending.add(FlowRecordBatch.Row.truncatedMarker(clock.millis()));
        }
        flush();
        try {
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new))
                    .get(Math.max(wait.toMillis(), 0), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnpublishedException("Interrupted while waiting for the run's records to be "
                    + "published.", e);
        } catch (ExecutionException e) {
            throw new UnpublishedException("The run's records could not be published: "
                    + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new UnpublishedException("Pulsar did not acknowledge the run's records within "
                    + wait.toSeconds() + " s.", e);
        }
    }

    private void flush() {
        lastFlush = clock.millis();
        if (pending.isEmpty()) {
            return;
        }
        FlowRecordBatch batch = new FlowRecordBatch();
        batch.setRunId(runId.toString());
        batch.setAttempt(attempt);
        batch.setSequence(sequence++);
        batch.setFlowExternalId(flowExternalId);
        batch.setFlowVersion(flowVersion);
        batch.setRecords(new ArrayList<>(pending));
        pending.clear();
        CompletableFuture<?> ack;
        try {
            ack = send.apply(batch);
        } catch (RuntimeException e) {
            ack = CompletableFuture.failedFuture(e);
        }
        sent.add(ack);
    }
}
