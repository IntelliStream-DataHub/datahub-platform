// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import java.util.List;

/**
 * One series over one window: an Arrow batch of {@code (timestamp, value)}, timestamps in
 * milliseconds UTC and values as doubles, neither nullable. The column names and types are those
 * of the platform's binary datapoint format for float series, so a batch read from DataHub or
 * written to it needs no conversion.
 *
 * <p>Rows are in the order they were added; nothing here sorts them. The batch is never changed
 * once built: {@link #root()} is for reading only, for code that works on whole columns or writes
 * the batch out as Arrow IPC.
 *
 * <p>Made through {@link ProcessSession#newTimeseries(int)}, so the engine owns its memory from the
 * first byte.
 */
public final class TimeseriesContent implements Content {

    public static final String TIMESTAMP = "timestamp";
    public static final String VALUE = "value";

    /** The schema every timeseries batch has. */
    public static final Schema SCHEMA = new Schema(List.of(
            new Field(TIMESTAMP, FieldType.notNullable(
                    new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC")), null),
            new Field(VALUE, FieldType.notNullable(
                    new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null)));

    private final VectorSchemaRoot root;
    private final TimeStampMilliTZVector timestamps;
    private final Float8Vector values;

    private TimeseriesContent(VectorSchemaRoot root) {
        this.root = root;
        this.timestamps = (TimeStampMilliTZVector) root.getVector(TIMESTAMP);
        this.values = (Float8Vector) root.getVector(VALUE);
    }

    /** An empty batch with {@link #SCHEMA}, for the engine to hand to a {@link Builder}. */
    public static VectorSchemaRoot newRoot(BufferAllocator allocator) {
        return VectorSchemaRoot.create(SCHEMA, allocator);
    }

    @Override
    public ContentKind kind() {
        return ContentKind.TIMESERIES;
    }

    public int rowCount() {
        return root.getRowCount();
    }

    /** Milliseconds since the epoch, UTC. */
    public long timestamp(int row) {
        return timestamps.get(row);
    }

    public double value(int row) {
        return values.get(row);
    }

    @Override
    public long size() {
        return (long) timestamps.getBufferSize() + values.getBufferSize();
    }

    /** The batch itself, for reading only. */
    public VectorSchemaRoot root() {
        return root;
    }

    /**
     * Fills an empty batch row by row. Processors get one from their session; the engine keeps the
     * batch's memory accounted for whether or not {@link #build()} is ever reached.
     */
    public static final class Builder {
        private final VectorSchemaRoot root;
        private final TimeStampMilliTZVector timestamps;
        private final Float8Vector values;
        private int rows;
        private boolean built;

        /** @param root an empty batch from {@link #newRoot(BufferAllocator)} */
        public Builder(VectorSchemaRoot root, int expectedRows) {
            if (!SCHEMA.equals(root.getSchema()) || root.getRowCount() != 0) {
                throw new IllegalArgumentException("A timeseries builder needs an empty batch with "
                        + "TimeseriesContent.SCHEMA");
            }
            this.root = root;
            this.timestamps = (TimeStampMilliTZVector) root.getVector(TIMESTAMP);
            this.values = (Float8Vector) root.getVector(VALUE);
            timestamps.allocateNew(Math.max(expectedRows, 1));
            values.allocateNew(Math.max(expectedRows, 1));
        }

        public Builder add(long timestampMillis, double value) {
            if (built) {
                throw new IllegalStateException("The batch is already built");
            }
            timestamps.setSafe(rows, timestampMillis);
            values.setSafe(rows, value);
            rows++;
            return this;
        }

        public int rowCount() {
            return rows;
        }

        public TimeseriesContent build() {
            if (built) {
                throw new IllegalStateException("The batch is already built");
            }
            built = true;
            root.setRowCount(rows);
            return new TimeseriesContent(root);
        }
    }
}
