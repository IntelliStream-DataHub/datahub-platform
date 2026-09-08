// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.sdk.http.DatahubApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs ingest tasks — each an item count plus a send action — concurrently: bounded fan-out across
 * virtual threads, retry of transient failures (HTTP 429/5xx/network) with backoff, and aggregation
 * into an {@link IngestResult}. Shared by the datapoint and event ingestors.
 */
final class BatchExecutor {

    private BatchExecutor() {
    }

    /** Sends one batch; throws {@link DatahubApiException} on failure. */
    @FunctionalInterface
    interface Send {
        void send();
    }

    /** A batch to send and how many items it carries (for the result counts). */
    record Task(int count, Send send) {
    }

    static IngestResult execute(List<Task> tasks, IngestOptions options) {
        if (tasks.isEmpty()) {
            return new IngestResult(0, 0, List.of());
        }

        Semaphore gate = new Semaphore(options.parallelism());
        AtomicLong succeeded = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        List<IngestResult.BatchError> errors = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Task task : tasks) {
                futures.add(executor.submit(() -> {
                    gate.acquire();
                    try {
                        sendWithRetry(task.send(), options);
                        succeeded.addAndGet(task.count());
                    } catch (RuntimeException e) {
                        failed.addAndGet(task.count());
                        int status = (e instanceof DatahubApiException de) ? de.statusCode() : 0;
                        String body = (e instanceof DatahubApiException de) ? de.body() : null;
                        errors.add(new IngestResult.BatchError(task.count(), status, e.getMessage(), body));
                    } finally {
                        gate.release();
                    }
                    return null;
                }));
            }
            awaitAll(futures);
        }

        IngestResult result = new IngestResult(succeeded.get(), failed.get(), new ArrayList<>(errors));
        if (options.failFast() && !result.isComplete()) {
            IngestResult.BatchError first = result.errors().get(0);
            throw new DatahubApiException(first.statusCode(),
                    "ingest failed (fail-fast): " + first.message()
                            + " [" + result.failed() + " items failed]", null);
        }
        return result;
    }

    private static void sendWithRetry(Send send, IngestOptions options) {
        for (int attempt = 0; ; attempt++) {
            try {
                send.send();
                return;
            } catch (DatahubApiException e) {
                if (!isRetryable(e.statusCode()) || attempt >= options.maxRetries()) {
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    private static boolean isRetryable(int status) {
        return status == 0 || status == 429 || status >= 500;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(2_000L, 100L * (1L << attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                // Batch failures are recorded, not thrown; anything here is unexpected.
                throw new DatahubApiException(0, "ingest task failed: " + e.getCause(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DatahubApiException(0, "ingest interrupted", null);
            }
        }
    }
}
