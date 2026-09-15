// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;

/**
 * Tuning for the binary datapoint ingest ({@code POST /timeseries/data/binary}). Frames are
 * always zstd-compressed; the level is the one knob, and 9 is the default because the client pays
 * for it and a small edge link is the usual bottleneck. Retries, parallelism and fail-fast behave
 * as in {@link IngestOptions}.
 */
public final class BinaryIngestOptions {

    private final int zstdLevel;
    private final boolean compressInParallel;
    private final int parallelism;
    private final int maxRetries;
    private final boolean failFast;

    private BinaryIngestOptions(Builder b) {
        if (b.zstdLevel != 1 && b.zstdLevel != 3 && b.zstdLevel != 9) {
            throw new IllegalArgumentException("zstdLevel must be 1, 3 or 9");
        }
        if (b.parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be > 0");
        }
        if (b.maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.zstdLevel = b.zstdLevel;
        this.compressInParallel = b.compressInParallel;
        this.parallelism = b.parallelism;
        this.maxRetries = b.maxRetries;
        this.failFast = b.failFast;
    }

    public int zstdLevel()               { return zstdLevel; }
    public boolean compressInParallel()  { return compressInParallel; }
    public int parallelism()             { return parallelism; }
    public int maxRetries()              { return maxRetries; }
    public boolean failFast()            { return failFast; }

    /** The equivalent executor options: same retry, parallelism and fail-fast rules. */
    IngestOptions executorOptions() {
        return IngestOptions.builder()
                .parallelism(parallelism)
                .maxRetries(maxRetries)
                .failFast(failFast)
                .build();
    }

    public static BinaryIngestOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int zstdLevel = ZstdPayloadCodec.DEFAULT_LEVEL;
        // A level-9 context needs about 30 MB; a small device may prefer one frame at a time.
        private boolean compressInParallel = true;
        private int parallelism = 8;
        private int maxRetries = 3;
        private boolean failFast = false;

        public Builder zstdLevel(int zstdLevel) {
            this.zstdLevel = zstdLevel;
            return this;
        }

        public Builder compressInParallel(boolean compressInParallel) {
            this.compressInParallel = compressInParallel;
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

        public BinaryIngestOptions build() {
            return new BinaryIngestOptions(this);
        }
    }
}
