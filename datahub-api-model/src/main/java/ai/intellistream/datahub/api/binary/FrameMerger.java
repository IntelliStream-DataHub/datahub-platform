// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnView;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon.Column;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon.Kind;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Concatenates the record batches of decoded frames of one value type into one Arrow stream whose
 * batches hold up to {@code maxRowsPerBatch} rows each. This is what turns many small frames into
 * the large blocks ClickHouse wants: fixed-width columns are copied, Utf8 offsets rebased, validity
 * bitmaps rebuilt.
 *
 * @see ArrowIpc why this package handles Arrow itself
 */
public final class FrameMerger {

    private FrameMerger() {
    }

    public static byte[] merge(DatapointValueType type, List<DatapointFrame> frames, int maxRowsPerBatch) {
        ArrowSchemaCanon canon = ArrowSchemaCanon.of(type);
        List<Batch> all = new ArrayList<>();
        for (DatapointFrame f : frames) {
            if (f.valueType() != type) {
                throw new IllegalArgumentException("frame " + f.index() + " is " + f.valueType() + ", merging " + type);
            }
            all.addAll(f.batches());
        }
        List<BatchData> merged = new ArrayList<>();
        List<Batch> group = new ArrayList<>();
        int groupRows = 0;
        for (Batch b : all) {
            if (!group.isEmpty() && groupRows + b.rows() > maxRowsPerBatch) {
                merged.add(concatenate(canon, group, groupRows));
                group = new ArrayList<>();
                groupRows = 0;
            }
            group.add(b);
            groupRows += b.rows();
        }
        if (!group.isEmpty()) {
            merged.add(concatenate(canon, group, groupRows));
        }
        return ArrowIpc.writeStream(canon, merged);
    }

    private static BatchData concatenate(ArrowSchemaCanon canon, List<Batch> group, int rows) {
        if (group.size() == 1) {
            Batch only = group.get(0);
            ColumnData[] columns = new ColumnData[only.columns().length];
            for (int c = 0; c < columns.length; c++) {
                ColumnView v = only.columns()[c];
                Column column = canon.columns().get(c);
                ByteBuffer validity = column.nullable() ? (v.validity() != null ? v.validity() : allSet(rows)) : null;
                columns[c] = new ColumnData(validity, v.nullCount(), v.offsets(), v.data());
            }
            return new BatchData(rows, columns);
        }
        List<Column> columns = canon.columns();
        ColumnData[] out = new ColumnData[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            Column column = columns.get(c);
            byte[] validity = column.nullable() ? new byte[(rows + 7) >>> 3] : null;
            int nulls = 0;
            if (column.kind() == Kind.UTF8) {
                ByteBuffer offsets = ByteBuffer.allocate((rows + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
                int total = 0;
                for (Batch b : group) {
                    ColumnView v = b.columns()[c];
                    total += v.offsetAt(b.rows()) - v.offsetAt(0);
                }
                ByteBuffer data = ByteBuffer.allocate(total);
                int row = 0;
                int base = 0;
                offsets.putInt(0);
                for (Batch b : group) {
                    ColumnView v = b.columns()[c];
                    int first = v.offsetAt(0);
                    for (int r = 0; r < b.rows(); r++, row++) {
                        offsets.putInt(base + v.offsetAt(r + 1) - first);
                        if (validity != null) {
                            if (v.isSet(r)) validity[row >>> 3] |= (byte) (1 << (row & 7)); else nulls++;
                        }
                    }
                    int length = v.offsetAt(b.rows()) - first;
                    data.put(v.data().slice(first, length));
                    base += length;
                }
                offsets.flip();
                data.flip();
                out[c] = new ColumnData(validity == null ? null : ByteBuffer.wrap(validity), nulls, offsets, data);
            } else {
                int width = column.kind().width();
                ByteBuffer data = ByteBuffer.allocate(rows * width).order(ByteOrder.LITTLE_ENDIAN);
                int row = 0;
                for (Batch b : group) {
                    ColumnView v = b.columns()[c];
                    data.put(v.data().slice(0, b.rows() * width));
                    if (validity != null) {
                        for (int r = 0; r < b.rows(); r++, row++) {
                            if (v.isSet(r)) validity[row >>> 3] |= (byte) (1 << (row & 7)); else nulls++;
                        }
                    }
                }
                data.flip();
                out[c] = new ColumnData(validity == null ? null : ByteBuffer.wrap(validity), nulls, null, data);
            }
        }
        return new BatchData(rows, out);
    }

    private static ByteBuffer allSet(int rows) {
        byte[] bits = new byte[(rows + 7) >>> 3];
        java.util.Arrays.fill(bits, (byte) 0xFF);
        return ByteBuffer.wrap(bits);
    }
}
