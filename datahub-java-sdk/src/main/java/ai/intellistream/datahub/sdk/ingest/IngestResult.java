// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.errors.Problem;

import java.util.List;

/**
 * Outcome of a concurrent ingestion: how many items landed, how many failed, and the per-batch
 * errors. Counts are in items (datapoints or events).
 */
public final class IngestResult {

    private final long succeeded;
    private final long failed;
    private final long buffered;
    private final List<BatchError> errors;

    public IngestResult(long succeeded, long failed, List<BatchError> errors) {
        this(succeeded, failed, errors, 0);
    }

    public IngestResult(long succeeded, long failed, List<BatchError> errors, long buffered) {
        this.succeeded = succeeded;
        this.failed = failed;
        this.errors = List.copyOf(errors);
        this.buffered = buffered;
    }

    /** Items that were ingested successfully. */
    public long succeeded() {
        return succeeded;
    }

    /** Items that could not be ingested (across failed batches). */
    public long failed() {
        return failed;
    }

    /**
     * Items currently held in the durable spool awaiting a retry, after this call. Always 0 unless a
     * buffer-retention window is configured on the client (see {@code DatahubConfig.bufferRetention}).
     */
    public long buffered() {
        return buffered;
    }

    /** One entry per failed batch. */
    public List<BatchError> errors() {
        return errors;
    }

    /** True when nothing failed and nothing is left buffered, i.e. everything is through. */
    public boolean isComplete() {
        return failed == 0 && buffered == 0;
    }

    /**
     * True when this is a failure the API says is worth sending again unchanged — every error
     * carries {@code retry: same-request}, or, where no problem document came back, is a network
     * error, a 429 or a 5xx.
     *
     * <p>Not the same question as {@link #isBufferable()}, and neither implies the other. A lost
     * optimistic lock is a 409 that is worth repeating at once but is not worth spooling; an expired
     * token is a 401 that is worth spooling but not worth repeating until someone renews it.
     */
    public boolean isTransientFailure() {
        if (failed == 0) {
            return false;
        }
        for (BatchError e : errors) {
            if (!isRetryable(e.statusCode(), e.problem())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether sending this same request again could succeed, as the API itself answers it.
     *
     * <p>{@code retry} is the contract's own word on it, so it decides where there is one: a 409
     * {@code optimistic-lock} says {@code same-request} and is worth another attempt, while a 500
     * {@code internal} says {@code needs-operator} and repeating it four more times only delays the
     * failure the caller has to handle anyway. The status is the fallback for the answers that carry
     * no problem document — a network error (status 0), a proxy's HTML 502, a gateway timeout.
     */
    public static boolean isRetryable(int status, Problem problem) {
        String retry = problem == null ? null : problem.retry();
        return retry != null ? Problem.RETRY_SAME_REQUEST.equals(retry) : isTransientStatus(status);
    }

    /**
     * True when this failure is worth spooling to the durable buffer for a later retry rather than
     * surfacing now: every error is either transient (network, HTTP 429 or 5xx) or an
     * authentication/authorization failure (HTTP 401/403). Treating 401/403 as bufferable lets data
     * keep accumulating on disk while an expired token or missing permission is fixed out-of-band, then
     * flush once it is restored. A terminal error (e.g. HTTP 400 bad request) makes this false, so it
     * surfaces instead of buffering forever.
     *
     * <p>One 403 is not bufferable: a tenant that has reached a permanent ceiling. That never becomes
     * acceptable by being replayed, so buffering it would fill the spool with data the server refuses
     * every time and bury the message that says the limit is raised by asking.
     *
     * <p>This asks the status, not the problem's {@code retry}, and deliberately. {@code retry}
     * answers "can this same request succeed later", which is a shorter horizon than the spool's:
     * an expired token is {@code change-request} because nothing about that request will work as it
     * stands, yet holding the data while someone renews the credential is exactly what the spool is
     * for. {@link #isRetryable} is where {@code retry} governs.
     */
    public boolean isBufferable() {
        if (failed == 0) {
            return false;
        }
        for (BatchError e : errors) {
            int s = e.statusCode();
            if (!isTransientStatus(s) && !isAuthFailure(s, e.problem())) {
                return false;
            }
        }
        return true;
    }

    /** Network error (status 0), rate limiting (429) or a server error (5xx) — a transient blip. */
    private static boolean isTransientStatus(int status) {
        return status == 0 || status == 429 || status >= 500;
    }

    /**
     * Unauthorized (401) or Forbidden (403), recoverable by fixing the credential out-of-band, with
     * one exception: the api also answers 403 when a tenant has reached a permanent ceiling on how
     * much it may hold. Nothing about that is fixed out-of-band by waiting, so spooling it would
     * fill the buffer with data the server will refuse every time it is replayed, and hide the one
     * message that says what to do (ask for the limit to be raised).
     */
    private static boolean isAuthFailure(int status, Problem problem) {
        if (status == 401) {
            return true;
        }
        return status == 403 && !problem.is(TENANT_LIMIT_REACHED);
    }

    /**
     * The one refusal that replaying can never turn into an acceptance. Matched on the problem
     * document's {@code type}, which is the member the API guarantees: the prose beside it is
     * written for a human and may be reworded or translated.
     */
    private static final String TENANT_LIMIT_REACHED = "tenant-limit-reached";

    /** A result that ingested nothing live but left {@code count} items buffered for a later retry. */
    public static IngestResult buffered(long count) {
        return new IngestResult(0, 0, List.of(), count);
    }

    /** A copy of this result with the spool depth set — used by the durable ingest path. */
    public IngestResult withBuffered(long buffered) {
        return new IngestResult(succeeded, failed, errors, buffered);
    }

    @Override
    public String toString() {
        return "IngestResult{succeeded=" + succeeded + ", failed=" + failed
                + ", buffered=" + buffered + ", errors=" + errors.size() + "}";
    }

    /**
     * A failed batch: how many items it carried, the HTTP status (0 if none), the message, and the
     * raw response body when the server sent one.
     *
     * <p>{@code problem} is that body read as the RFC 9457 document the API sends, and is never
     * null. The status alone does not always say whether retrying could ever work — a 403 is usually
     * a credential to fix, but it is also how the API reports a tenant that has reached a permanent
     * ceiling, and only {@code type} tells them apart. See {@link #isBufferable()}.
     */
    public record BatchError(int datapointCount, int statusCode, String message, String body,
                             Problem problem) {

        public BatchError(int datapointCount, int statusCode, String message) {
            this(datapointCount, statusCode, message, null);
        }

        public BatchError(int datapointCount, int statusCode, String message, String body) {
            this(datapointCount, statusCode, message, body, null);
        }

        /** Never null: an answer that was not a problem document yields one carrying just the status. */
        public BatchError {
            problem = problem == null ? Problem.of(statusCode, body) : problem;
        }
    }
}
