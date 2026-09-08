// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

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
     * True when this is a failure whose every error is transient (network, HTTP 429 or 5xx), i.e. a
     * blip worth a quick retry. This is narrower than {@link #isBufferable()}, which also covers auth
     * failures. A terminal error (e.g. HTTP 400) makes this false.
     */
    public boolean isTransientFailure() {
        if (failed == 0) {
            return false;
        }
        for (BatchError e : errors) {
            if (!isTransientStatus(e.statusCode())) {
                return false;
            }
        }
        return true;
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
     */
    public boolean isBufferable() {
        if (failed == 0) {
            return false;
        }
        for (BatchError e : errors) {
            int s = e.statusCode();
            if (!isTransientStatus(s) && !isAuthFailure(s, e.body())) {
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
    private static boolean isAuthFailure(int status, String body) {
        if (status == 401) {
            return true;
        }
        return status == 403 && !isTenantLimit(body);
    }

    /**
     * Matched on the problem document's stable {@code type} URI rather than its wording, which is
     * prose and may be translated or reworded. Substring rather than a parse, so the lean client
     * needs no JSON reader on an error path.
     */
    private static boolean isTenantLimit(String body) {
        return body != null && body.contains("intellistream.ai/errors/tenant-limit-reached");
    }

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
     * <p>The body is kept because the status alone does not always say whether retrying could ever
     * work: a 403 is usually a credential to fix, but it is also how the api reports a tenant that
     * has reached a permanent ceiling. See {@link #isBufferable()}.
     */
    public record BatchError(int datapointCount, int statusCode, String message, String body) {

        public BatchError(int datapointCount, int statusCode, String message) {
            this(datapointCount, statusCode, message, null);
        }
    }
}
