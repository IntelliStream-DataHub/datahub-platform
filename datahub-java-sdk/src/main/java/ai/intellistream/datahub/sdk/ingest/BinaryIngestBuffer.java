// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.sdk.services.TimeseriesService;
import ai.intellistream.datahub.sdk.timeseries.Datapoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Small inserts on the binary path: points are added one at a time and sent as frames once
 * {@code maxPoints} have accumulated or the oldest pending point is {@code maxAge} old, whichever
 * comes first. Adding never blocks on the network; flushes run on a thread of their own and hand
 * each {@link IngestResult} to the listener. Close it to flush what is left.
 */
public final class BinaryIngestBuffer implements AutoCloseable {

    public static final int DEFAULT_MAX_POINTS = 10_000;
    public static final Duration DEFAULT_MAX_AGE = Duration.ofMillis(200);

    private final TimeseriesService service;
    private final BinaryIngestOptions options;
    private final int maxPoints;
    private final long maxAgeNanos;
    private final Consumer<IngestResult> onFlush;
    private final Object lock = new Object();
    private final ExecutorService flusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "datahub-binary-ingest-buffer");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "datahub-binary-ingest-buffer-timer");
        t.setDaemon(true);
        return t;
    });

    private Map<String, List<Datapoint>> pending = new LinkedHashMap<>();
    private int pendingCount;
    private long oldestNanos;
    private boolean flushScheduled;
    private volatile IngestResult lastResult;
    private volatile boolean closed;

    public BinaryIngestBuffer(TimeseriesService service, BinaryIngestOptions options,
                              int maxPoints, Duration maxAge, Consumer<IngestResult> onFlush) {
        if (maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints must be > 0");
        }
        this.service = service;
        this.options = options;
        this.maxPoints = maxPoints;
        this.maxAgeNanos = maxAge.toNanos();
        this.onFlush = onFlush == null ? r -> { } : onFlush;
        long tick = Math.max(10, maxAge.toMillis() / 2);
        timer.scheduleAtFixedRate(this::flushIfStale, tick, tick, TimeUnit.MILLISECONDS);
    }

    public void add(String externalId, Instant timestamp, double value) {
        add(externalId, Datapoint.of(timestamp, value));
    }

    public void add(String externalId, Instant timestamp, long value) {
        add(externalId, Datapoint.of(timestamp, value));
    }

    public void add(String externalId, Instant timestamp, String value) {
        add(externalId, Datapoint.of(timestamp, value));
    }

    public void add(String externalId, Datapoint point) {
        if (closed) {
            throw new IllegalStateException("buffer is closed");
        }
        boolean full;
        synchronized (lock) {
            if (pendingCount == 0) {
                oldestNanos = System.nanoTime();
            }
            pending.computeIfAbsent(externalId, k -> new ArrayList<>()).add(point);
            pendingCount++;
            full = pendingCount >= maxPoints;
            if (full && !flushScheduled) {
                flushScheduled = true;
            } else {
                full = false;
            }
        }
        if (full) {
            flusher.submit(this::flush);
        }
    }

    /** Points added and not yet handed to a flush. */
    public int pending() {
        synchronized (lock) {
            return pendingCount;
        }
    }

    /** The outcome of the most recent flush, or null before the first. */
    public IngestResult lastResult() {
        return lastResult;
    }

    /** Sends everything pending now, on the calling thread, and returns the result. */
    public IngestResult flush() {
        Map<String, List<Datapoint>> snapshot;
        synchronized (lock) {
            flushScheduled = false;
            if (pendingCount == 0) {
                return lastResult;
            }
            snapshot = pending;
            pending = new LinkedHashMap<>();
            pendingCount = 0;
        }
        IngestResult result = service.ingestBinary(snapshot, options);
        lastResult = result;
        onFlush.accept(result);
        return result;
    }

    private void flushIfStale() {
        boolean stale;
        synchronized (lock) {
            stale = pendingCount > 0 && !flushScheduled && System.nanoTime() - oldestNanos >= maxAgeNanos;
            if (stale) {
                flushScheduled = true;
            }
        }
        if (stale) {
            flusher.submit(this::flush);
        }
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
        flusher.shutdown();
        try {
            flusher.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }
}
