// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DatapointFrameWriter#wrapStream} cuts a series into full frames whatever ClickHouse's
 * record batches look like, and {@link Batch#slice} keeps every row's bytes while doing so, the
 * re-packed validity bitmap and rebased text offsets included.
 */
class DatapointFrameWrapStreamTest {

    private static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);
    private static final long T0 = 1_700_000_000_000L;

    /** One text record batch of {@code rows} rows, as ClickHouse may answer whatever the frame cap. */
    private static BatchData textBatch(long id, int rows) {
        ByteBuffer ids = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer timestamps = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer offsets = ByteBuffer.allocate((rows + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        for (int r = 0; r < rows; r++) {
            ids.putLong(r * 8, id);
            timestamps.putLong(r * 8, T0 + r);
            offsets.putInt(r * 4, text.size());
            text.writeBytes(("v" + r).getBytes(StandardCharsets.UTF_8));
        }
        offsets.putInt(rows * 4, text.size());
        return new BatchData(rows, new ColumnData[]{
                new ColumnData(null, 0, null, ids),
                new ColumnData(null, 0, null, timestamps),
                new ColumnData(null, 0, offsets, ByteBuffer.wrap(text.toByteArray()))});
    }

    @Test
    void aBatchLongerThanAFrameIsCutIntoFullFrames() throws Exception {
        int rows = 20_005;
        byte[] stream = ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.TEXT), List.of(textBatch(7, rows)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DatapointFrameWriter.wrapStream(DatapointValueType.TEXT, false, 7, "s7", new ByteArrayInputStream(stream), ZSTD, body);
        body.writeBytes(FrameLimits.END_OF_RESPONSE);

        List<DatapointFrame> frames = DatapointFrame.parseResponse(body.toByteArray(), ZSTD);
        assertEquals(List.of(10_000, 10_000, 5), frames.stream().map(DatapointFrame::rowCount).toList());
        assertEquals("v0", frames.get(0).valueAsString(0));
        assertEquals("v9999", frames.get(0).valueAsString(9999));
        assertEquals("v10000", frames.get(1).valueAsString(0));
        assertEquals(T0 + 10_000, frames.get(1).timestamp(0));
        assertEquals("v20004", frames.get(2).valueAsString(4));
        assertEquals(T0 + 20_004, frames.get(2).timestamp(4));
        for (DatapointFrame f : frames) {
            assertEquals("s7", f.externalIds()[0]);
        }
    }

    @Test
    void aSchemaAloneWritesNoFrame() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DatapointFrameWriter.wrapStream(DatapointValueType.FLOAT, false, 1, "s1",
                new ByteArrayInputStream(ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT), List.of())), ZSTD, body);
        assertEquals(0, body.size());
    }

    @Test
    void aSliceKeepsEveryRowOfAMixedBatchAtAnUnalignedOffset() {
        // Numeric and text rows alternate in runs, so both value columns hold nulls and the
        // validity bitmaps are re-packed from bit 3, not a byte boundary.
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.MIXED).series(9, "s9");
        for (int r = 0; r < 20; r++) {
            w.addMixed(9, T0 + r, (r / 3) % 2 == 0 ? Integer.toString(r) : "FAULT" + r);
        }
        DatapointFrame whole = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
        Batch batch = whole.batches().get(0);
        assertEquals(20, batch.rows());

        BatchData sliced = batch.slice(ArrowSchemaCanon.of(DatapointValueType.MIXED), 3, 11);
        DatapointFrame part = DatapointFrame.parseAll(
                DatapointFrameWriter.wrap(DatapointValueType.MIXED, false, 9, "s9", List.of(sliced), ZSTD), ZSTD).get(0);
        assertEquals(8, part.rowCount());
        for (int r = 0; r < 8; r++) {
            assertEquals(whole.timestamp(3 + r), part.timestamp(r), "row " + r);
            assertEquals(whole.valueAsString(3 + r), part.valueAsString(r), "row " + r);
        }
        assertTrue(part.valueAsString(0).startsWith("FAULT"), part.valueAsString(0));
    }

    @Test
    void aSliceOfANarrowColumnIsTheRowsAsked() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(5, "s5");
        for (int r = 0; r < 5; r++) {
            w.addFloat32(5, T0 + r, r + 0.5f);
        }
        DatapointFrame whole = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
        BatchData sliced = whole.batches().get(0).slice(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), 1, 4);
        DatapointFrame part = DatapointFrame.parseAll(
                DatapointFrameWriter.wrap(DatapointValueType.FLOAT32, false, 5, "s5", List.of(sliced), ZSTD), ZSTD).get(0);
        assertEquals(3, part.rowCount());
        for (int r = 0; r < 3; r++) {
            assertEquals(whole.valueAsString(1 + r), part.valueAsString(r), "row " + r);
            assertEquals(T0 + 1 + r, part.timestamp(r));
        }
    }
}
