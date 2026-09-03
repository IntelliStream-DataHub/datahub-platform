// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

/**
 * The tenant has spent its ingest allowance for the current UTC day.
 *
 * <p>Temporary by construction: the window rolls at midnight, so this is a 429 with a
 * {@code Retry-After} pointing there rather than a permanent refusal.
 */
public class IngestQuotaExceededException extends LimitException {

    private final String metric;
    private final long limit;
    private final long retryAfterSeconds;

    public IngestQuotaExceededException(String metric, long limit, long retryAfterSeconds) {
        super("Daily %s ingest quota (%d) is spent; it resets at 00:00 UTC.".formatted(metric, limit));
        this.metric = metric;
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getMetric() {
        return metric;
    }

    public long getLimit() {
        return limit;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
