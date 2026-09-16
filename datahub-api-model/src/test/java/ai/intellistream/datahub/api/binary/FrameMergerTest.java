// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The consumer's merge: many small frames become one stream of large batches, and every row keeps
 * its value, including Utf8 offsets rebased across frames and validity bitmaps rebuilt for mixed.
 */
class FrameMergerTest {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);

    @Test
    void mergesFixedWidthFramesIntoBatchesUnderTheCap() {
        List<DatapointFrame> frames = new ArrayList<>();
        for (int f = 0; f < 5; f++) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(f, "s" + f);
            for (int r = 0; r < 100; r++) {
                w.addFloat32(f, r, f * 1000 + r);
            }
            frames.add(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0));
        }
        byte[] merged = FrameMerger.merge(DatapointValueType.FLOAT32, frames, 250);
        List<Batch> batches = ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), merged, 0, 1_000_000);
        assertEquals(3, batches.size());
        assertEquals(200, batches.get(0).rows());
        assertEquals(200, batches.get(1).rows());
        assertEquals(100, batches.get(2).rows());
        assertEquals(1000f + 50, batches.get(0).columns()[2].data().getFloat(150 * 4));
        assertEquals(1L, batches.get(0).columns()[0].data().getLong(150 * 8));
        assertEquals(4000f + 99, batches.get(2).columns()[2].data().getFloat(99 * 4));
    }

    @Test
    void rebasesUtf8OffsetsAndRebuildsMixedBitmaps() {
        List<DatapointFrame> frames = new ArrayList<>();
        String[] values = {"open", "1.5", "closed", "2", "half"};
        for (int f = 0; f < 3; f++) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.MIXED).series(f, "m" + f);
            for (int r = 0; r < values.length; r++) {
                w.addMixed(f, r, values[r]);
            }
            frames.add(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0));
        }
        byte[] merged = FrameMerger.merge(DatapointValueType.MIXED, frames, 1_000_000);
        List<Batch> batches = ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.MIXED), merged, 0, 1_000_000);
        assertEquals(1, batches.size());
        Batch b = batches.get(0);
        assertEquals(15, b.rows());
        assertEquals(9, b.columns()[2].nullCount());
        assertEquals(6, b.columns()[3].nullCount());
        for (int f = 0; f < 3; f++) {
            for (int r = 0; r < values.length; r++) {
                int row = f * values.length + r;
                boolean numeric = r == 1 || r == 3;
                assertEquals(numeric, b.columns()[2].isSet(row));
                assertEquals(!numeric, b.columns()[3].isSet(row));
                if (numeric) {
                    assertEquals(Double.parseDouble(values[r]), b.columns()[2].data().getDouble(row * 8));
                } else {
                    assertEquals(values[r], b.columns()[3].utf8At(row));
                }
            }
        }
    }

    @Test
    void mergesTextFramesOfDifferentLengths() {
        List<DatapointFrame> frames = new ArrayList<>();
        DatapointFrameWriter a = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(1, "a");
        a.addText(1, 1, "first");
        a.addText(1, 2, "second value");
        frames.add(DatapointFrame.parseAll(a.build(ZSTD), ZSTD).get(0));
        DatapointFrameWriter b = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(2, "b");
        b.addText(2, 1, "æøå");
        frames.add(DatapointFrame.parseAll(b.build(ZSTD), ZSTD).get(0));
        byte[] merged = FrameMerger.merge(DatapointValueType.TEXT, frames, 1_000_000);
        Batch batch = ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.TEXT), merged, 0, 1_000_000).get(0);
        assertEquals(3, batch.rows());
        assertEquals("first", batch.columns()[2].utf8At(0));
        assertEquals("second value", batch.columns()[2].utf8At(1));
        assertEquals("æøå", batch.columns()[2].utf8At(2));
        assertEquals(2L, batch.columns()[0].data().getLong(16));
    }
}
