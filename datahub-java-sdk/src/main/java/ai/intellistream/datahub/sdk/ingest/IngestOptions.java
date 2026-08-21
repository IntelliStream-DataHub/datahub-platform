// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

/**
 * Tuning for concurrent ingestion. Datapoints are split into batches of at most
 * {@link #batchSize()} and sent with up to {@link #parallelism()} requests in flight;
 * transient failures (HTTP 429/5xx, network) are retried up to {@link #maxRetries()} times.
 */
public final class IngestOptions {

    private final int batchSize;
    private final int parallelism;
    private final int maxRetries;
    private final boolean failFast;

    private IngestOptions(Builder b) {
        if (b.batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        if (b.parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be > 0");
        }
        if (b.maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.batchSize = b.batchSize;
        this.parallelism = b.parallelism;
        this.maxRetries = b.maxRetries;
        this.failFast = b.failFast;
    }

    public int batchSize()     { return batchSize; }
    public int parallelism()   { return parallelism; }
    public int maxRetries()    { return maxRetries; }
    public boolean failFast()  { return failFast; }

    public static IngestOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int batchSize = 10_000;   // datapoints per request; the analytics store likes big batches
        private int parallelism = 8;      // concurrent in-flight requests
        private int maxRetries = 3;
        private boolean failFast = false; // by default collect errors rather than abort

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        public IngestOptions build() {
            return new IngestOptions(this);
        }
    }
}
