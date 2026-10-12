// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.runs.RunRepository.Claimed;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A run this instance is executing: its lease as this instance knows it, and whether and why it
 * should stop.
 *
 * <p>The lease is kept as a deadline on this JVM's monotonic clock, set from what the database
 * answered — the time it had left by the database's clock — counted from <em>before</em> the
 * statement was sent. That deadline is never later than the real expiry, so whatever this instance
 * concludes from it errs towards stopping early, never towards writing after another instance has
 * taken the run over.
 *
 * <p>Read from the run's own thread and from the SDK's ingest threads (the fence), written by the
 * heartbeat: hence the volatile fields, and no thread-locals.
 */
final class ActiveRun {

    /** Why a run is being stopped; the first reason given wins. */
    enum Stop {
        /** Someone cancelled it. */
        CANCELLED,
        /** It ran past its timeout. */
        TIMEOUT,
        /** Its lease ran out or was taken: it is no longer this instance's to finish. */
        LEASE_LOST,
        /** This instance is shutting down; the run goes back to pending as it was. */
        SHUTDOWN
    }

    final String tenantId;
    final Claimed claimed;
    /**
     * The bearer tokens a preview runs with — its caller's own — or null for a run that acts as its
     * flow's deployed identity. Held only here: the token never reaches the database, which is why
     * no other instance can take a preview over.
     */
    final Supplier<String> tokens;
    private final LongSupplier nanoTime;
    private final long timeoutDeadline;
    private volatile long leaseDeadline;
    private final AtomicReference<Stop> stop = new AtomicReference<>();

    /** @param sentAt {@link #nanoTime} just before the claim was sent */
    ActiveRun(String tenantId, Claimed claimed, long sentAt, LongSupplier nanoTime) {
        this(tenantId, claimed, null, sentAt, nanoTime);
    }

    ActiveRun(String tenantId, Claimed claimed, Supplier<String> tokens, long sentAt,
              LongSupplier nanoTime) {
        this.tenantId = tenantId;
        this.claimed = claimed;
        this.tokens = tokens;
        this.nanoTime = nanoTime;
        this.leaseDeadline = sentAt + claimed.leaseLeft().toNanos();
        this.timeoutDeadline = sentAt + claimed.timeout().toNanos();
    }

    UUID id() {
        return claimed.id();
    }

    /** A heartbeat renewed the lease; {@code sentAt} is when that statement was sent. */
    void renewed(long sentAt, Duration leaseLeft) {
        leaseDeadline = sentAt + leaseLeft.toNanos();
    }

    void stop(Stop reason) {
        stop.compareAndSet(null, reason);
    }

    Stop stopReason() {
        return stop.get();
    }

    /** Whether the run should stop now; notices its own timeout and an expired lease. */
    boolean shouldStop() {
        long now = nanoTime.getAsLong();
        if (now - timeoutDeadline > 0) {
            stop(Stop.TIMEOUT);
        }
        if (now - leaseDeadline > 0) {
            stop(Stop.LEASE_LOST);
        }
        return stop.get() != null;
    }

    /** How long the run has left before its timeout; negative once it is past it. */
    Duration timeLeft() {
        return Duration.ofNanos(timeoutDeadline - nanoTime.getAsLong());
    }

    /**
     * Whether the lease will still be held when a call taking up to {@code callTimeout} finishes.
     * The fence: the run's DataHub client asks this before every request and sends nothing when the
     * answer is no.
     */
    boolean leaseCovers(Duration callTimeout) {
        return stop.get() != Stop.LEASE_LOST
                && leaseDeadline - (nanoTime.getAsLong() + callTimeout.toNanos()) > 0;
    }
}
