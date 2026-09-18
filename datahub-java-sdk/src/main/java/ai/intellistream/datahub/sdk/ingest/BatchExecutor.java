// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.errors.Problem;
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
 * virtual threads, retry of what the API says is worth retrying (see
 * {@link IngestResult#isRetryable}) with backoff, and aggregation into an {@link IngestResult}.
 * Shared by the datapoint and event ingestors.
 */
final class BatchExecutor {

    /** A Retry-After past this is not waited out: the batch fails now and is held somewhere better. */
    private static final long MAX_RETRY_AFTER_MILLIS = 30_000L;

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
                        Problem problem = (e instanceof DatahubApiException de) ? de.problem() : Problem.of(0, null);
                        errors.add(new IngestResult.BatchError(task.count(), status, e.getMessage(), body, problem));
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
                            + " [" + result.failed() + " items failed]", first.body());
        }
        return result;
    }

    private static void sendWithRetry(Send send, IngestOptions options) {
        for (int attempt = 0; ; attempt++) {
            try {
                send.send();
                return;
            } catch (DatahubApiException e) {
                if (!IngestResult.isRetryable(e.statusCode(), e.problem()) || attempt >= options.maxRetries()) {
                    throw e;
                }
                // A wait longer than any one attempt should hold a calling thread. The server has
                // said when the allowance returns — a spent daily ingest quota returns at midnight
                // UTC — so retrying sooner only spends attempts being refused again. Fail now and
                // let the caller, or the durable spool, hold the batch until then.
                if (e.retryAfterSeconds() * 1000L > MAX_RETRY_AFTER_MILLIS) {
                    throw e;
                }
                backoff(attempt, e.retryAfterSeconds());
            }
        }
    }

    /**
     * Exponential backoff, floored at the {@code Retry-After} the server asked for. The API sends
     * that header with its 429s — a rate limit, a spent daily ingest allowance, too many binary
     * requests in flight — and it knows when the allowance returns, so guessing shorter only spends
     * a retry to be refused again. A delay past {@link #MAX_RETRY_AFTER_MILLIS} never gets here:
     * the caller sees the failure instead of a parked thread.
     */
    private static void backoff(int attempt, long retryAfterSeconds) {
        long millis = Math.min(2_000L, 100L * (1L << attempt));
        if (retryAfterSeconds > 0) {
            millis = Math.max(millis, retryAfterSeconds * 1000L);
        }
        try {
            Thread.sleep(millis);
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
