// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import java.util.Arrays;
import java.util.function.LongConsumer;

/**
 * One series over one window: {@code (timestamp, value)} rows, timestamps in milliseconds UTC and
 * values as doubles. The same columns as the platform's binary datapoint format for float series,
 * which the source and sink read and write with api-model's frame tooling.
 *
 * <p>Held on the heap, in two arrays. Arrow's Java library would hold it off-heap, but on Java 25
 * it does not start without extra JVM flags, which is why api-model does not use it either.
 *
 * <p>Rows are in the order they were added; nothing here sorts them. The content never changes
 * once built.
 *
 * <p>Made through {@link ProcessSession#newTimeseries(int)}, so the run's memory cap counts it from
 * the first row.
 */
public final class TimeseriesContent implements Content {

    /** Bytes one row takes: a long and a double. */
    public static final int ROW_BYTES = Long.BYTES + Double.BYTES;

    private final long[] timestamps;
    private final double[] values;
    private final int rows;

    private TimeseriesContent(long[] timestamps, double[] values, int rows) {
        this.timestamps = timestamps;
        this.values = values;
        this.rows = rows;
    }

    @Override
    public ContentKind kind() {
        return ContentKind.TIMESERIES;
    }

    public int rowCount() {
        return rows;
    }

    /** Milliseconds since the epoch, UTC. */
    public long timestamp(int row) {
        return timestamps[checked(row)];
    }

    public double value(int row) {
        return values[checked(row)];
    }

    /** What the content holds, as counted against the run's memory cap: its arrays' capacity. */
    @Override
    public long size() {
        return (long) timestamps.length * ROW_BYTES;
    }

    private int checked(int row) {
        if (row < 0 || row >= rows) {
            throw new IndexOutOfBoundsException("Row " + row + " of " + rows);
        }
        return row;
    }

    /**
     * Fills a series row by row. Processors get one from their session; every byte it grows by is
     * counted against the run's memory cap first, whether or not {@link #build()} is ever reached.
     */
    public static final class Builder {
        private final LongConsumer memory;
        private long[] timestamps;
        private double[] values;
        private int rows;
        private TimeseriesContent built;

        /**
         * @param memory charged with the bytes of every allocation before it is made; throws when
         *               the run's cap would be exceeded
         */
        public Builder(int expectedRows, LongConsumer memory) {
            this.memory = memory;
            int capacity = Math.max(expectedRows, 1);
            memory.accept((long) capacity * ROW_BYTES);
            this.timestamps = new long[capacity];
            this.values = new double[capacity];
        }

        public Builder add(long timestampMillis, double value) {
            if (built != null) {
                throw new IllegalStateException("The series is already built");
            }
            if (rows == timestamps.length) {
                int capacity = (int) Math.min(Integer.MAX_VALUE - 8, Math.max(16L, 2L * rows));
                memory.accept((long) (capacity - rows) * ROW_BYTES);
                timestamps = Arrays.copyOf(timestamps, capacity);
                values = Arrays.copyOf(values, capacity);
            }
            timestamps[rows] = timestampMillis;
            values[rows] = value;
            rows++;
            return this;
        }

        public int rowCount() {
            return rows;
        }

        /** The bytes charged to the run's cap so far. */
        public long size() {
            return (long) timestamps.length * ROW_BYTES;
        }

        /** The series built from this builder, or null before {@link #build()}. */
        public TimeseriesContent built() {
            return built;
        }

        public TimeseriesContent build() {
            if (built != null) {
                throw new IllegalStateException("The series is already built");
            }
            built = new TimeseriesContent(timestamps, values, rows);
            return built;
        }
    }
}
