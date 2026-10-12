// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnData;
import ai.intellistream.datahub.api.binary.ArrowIpc.RawBatch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DatapointFrameWriter#relayStream} answers a read with the record batches ClickHouse
 * compressed, untouched, packed into frames under the row cap; only a batch it cannot relay is
 * decoded, cut and compressed here. Every frame it writes is one ingest takes back.
 */
class DatapointFrameRelayTest {

    private static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);
    private static final long T0 = 1_700_000_000_000L;

    /** One text record batch of {@code rows} rows from {@code first}, as ClickHouse may answer whatever the frame cap. */
    private static BatchData textBatch(long id, int first, int rows) {
        ByteBuffer ids = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer timestamps = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer offsets = ByteBuffer.allocate((rows + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        for (int r = 0; r < rows; r++) {
            ids.putLong(r * 8, id);
            timestamps.putLong(r * 8, T0 + first + r);
            offsets.putInt(r * 4, text.size());
            text.writeBytes(("v" + (first + r)).getBytes(StandardCharsets.UTF_8));
        }
        offsets.putInt(rows * 4, text.size());
        return new BatchData(rows, new ColumnData[]{
                new ColumnData(null, 0, null, ids),
                new ColumnData(null, 0, null, timestamps),
                new ColumnData(null, 0, offsets, ByteBuffer.wrap(text.toByteArray()))});
    }

    private static BatchData floatBatch(long id, int first, int rows) {
        ByteBuffer ids = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer timestamps = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer values = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int r = 0; r < rows; r++) {
            ids.putLong(r * 8, id);
            timestamps.putLong(r * 8, T0 + first + r);
            values.putDouble(r * 8, first + r + 0.25);
        }
        return new BatchData(rows, new ColumnData[]{
                new ColumnData(null, 0, null, ids), new ColumnData(null, 0, null, timestamps), new ColumnData(null, 0, null, values)});
    }

    /** The frames the relay writes for {@code stream}, with the end marker a response carries. */
    private static byte[] relay(DatapointValueType type, boolean aggregates, long id, byte[] stream) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DatapointFrameWriter.relayStream(type, aggregates, id, "s" + id, new ByteArrayInputStream(stream), ZSTD, body);
        body.writeBytes(FrameLimits.END_OF_RESPONSE);
        return body.toByteArray();
    }

    /** Each record batch message of {@code stream} as sent, to look for in a frame. */
    private static List<byte[]> messagesOf(ArrowSchemaCanon canon, byte[] stream) throws IOException {
        ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(canon, new ByteArrayInputStream(stream), 0, Integer.MAX_VALUE, 1 << 30, ZSTD);
        List<byte[]> messages = new ArrayList<>();
        for (RawBatch raw = reader.nextRaw(); raw != null; raw = reader.nextRaw()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            raw.writeTo(out);
            messages.add(out.toByteArray());
        }
        return messages;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @Test
    void wholeBatchesAreRelayedAsTheyCame() throws Exception {
        ArrowSchemaCanon canon = ArrowSchemaCanon.of(DatapointValueType.TEXT);
        byte[] stream = ArrowIpc.writeStream(canon, List.of(textBatch(7, 0, 3000), textBatch(7, 3000, 3000), textBatch(7, 6000, 3000)), ZSTD);
        List<DatapointFrame> frames = DatapointFrame.parseResponse(relay(DatapointValueType.TEXT, false, 7, stream), ZSTD);

        assertEquals(1, frames.size());
        DatapointFrame frame = frames.get(0);
        assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, frame.compression());
        assertEquals(9000, frame.rowCount());
        assertEquals(3, frame.batches().size());
        byte[] payload = frame.payloadBytes();
        int at = 0;
        for (byte[] message : messagesOf(canon, stream)) {
            int found = indexOf(payload, message);
            assertTrue(found > at, "the batch message is in the payload, after the previous one");
            at = found;
        }
        assertEquals("v0", frame.valueAsString(0));
        assertEquals("v8999", frame.valueAsString(8999));
        assertEquals(T0 + 8999, frame.timestamp(8999));
    }

    @Test
    void batchesArePackedUpToTheCapAndNeverCut() throws Exception {
        ArrowSchemaCanon canon = ArrowSchemaCanon.of(DatapointValueType.FLOAT);
        byte[] stream = ArrowIpc.writeStream(canon, List.of(floatBatch(1, 0, 40_000), floatBatch(1, 40_000, 40_000), floatBatch(1, 80_000, 40_000)), ZSTD);
        List<DatapointFrame> frames = DatapointFrame.parseResponse(relay(DatapointValueType.FLOAT, false, 1, stream), ZSTD);

        assertEquals(List.of(80_000, 40_000), frames.stream().map(DatapointFrame::rowCount).toList());
        assertEquals(List.of(2, 1), frames.stream().map(f -> f.batches().size()).toList());
        assertEquals("40000.25", frames.get(0).valueAsString(40_000));
        assertEquals("119999.25", frames.get(1).valueAsString(39_999));
        assertEquals(T0 + 119_999, frames.get(1).timestamp(39_999));
    }

    @Test
    void aBatchLongerThanAFrameIsCutIntoPiecesOfTheCap() throws Exception {
        // 20 005 rows in one batch, then 5 000 in another: two full frames, and the 5-row tail
        // of the cut shares its frame with the whole batch that follows.
        ArrowSchemaCanon canon = ArrowSchemaCanon.of(DatapointValueType.TEXT);
        byte[] stream = ArrowIpc.writeStream(canon, List.of(textBatch(7, 0, 20_005), textBatch(7, 20_005, 5_000)), ZSTD);
        List<DatapointFrame> frames = DatapointFrame.parseResponse(relay(DatapointValueType.TEXT, false, 7, stream), ZSTD);

        assertEquals(List.of(10_000, 10_000, 5_005), frames.stream().map(DatapointFrame::rowCount).toList());
        for (DatapointFrame f : frames) {
            assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, f.compression());
            assertEquals("s7", f.externalIds()[0]);
        }
        assertEquals("v0", frames.get(0).valueAsString(0));
        assertEquals("v9999", frames.get(0).valueAsString(9999));
        assertEquals("v10000", frames.get(1).valueAsString(0));
        assertEquals(T0 + 10_000, frames.get(1).timestamp(0));
        assertEquals("v20004", frames.get(2).valueAsString(4));
        assertEquals("v20005", frames.get(2).valueAsString(5));
        assertEquals(T0 + 25_004, frames.get(2).timestamp(5_004));
    }

    @Test
    void aBatchThatCameUncompressedIsCompressedHere() throws Exception {
        byte[] stream = ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT), List.of(floatBatch(1, 0, 10)));
        List<DatapointFrame> frames = DatapointFrame.parseResponse(relay(DatapointValueType.FLOAT, false, 1, stream), ZSTD);
        assertEquals(1, frames.size());
        assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, frames.get(0).compression());
        assertEquals("9.25", frames.get(0).valueAsString(9));
    }

    @Test
    void aSchemaAloneWritesNoFrame() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DatapointFrameWriter.relayStream(DatapointValueType.FLOAT, false, 1, "s1",
                new ByteArrayInputStream(ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT), List.of(), ZSTD)), ZSTD, body);
        assertEquals(0, body.size());
    }

    @Test
    void aggregateBatchesRelayAsAggregateFrames() throws Exception {
        int rows = 3;
        ByteBuffer ids = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer buckets = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer[] stats = new ByteBuffer[4];
        for (int s = 0; s < 4; s++) stats[s] = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int r = 0; r < rows; r++) {
            ids.putLong(r * 8, 4);
            buckets.putLong(r * 8, 3_600_000L * r);
            for (int s = 0; s < 4; s++) stats[s].putDouble(r * 8, r + s / 10.0);
        }
        BatchData batch = new BatchData(rows, new ColumnData[]{
                new ColumnData(null, 0, null, ids), new ColumnData(null, 0, null, buckets),
                new ColumnData(null, 0, null, stats[0]), new ColumnData(null, 0, null, stats[1]),
                new ColumnData(null, 0, null, stats[2]), new ColumnData(null, 0, null, stats[3])});
        byte[] stream = ArrowIpc.writeStream(ArrowSchemaCanon.aggregates(), List.of(batch), ZSTD);
        DatapointFrame frame = DatapointFrame.parseResponse(relay(DatapointValueType.FLOAT, true, 4, stream), ZSTD).get(0);
        assertTrue(frame.aggregates());
        assertEquals(7_200_000, frame.timestamp(2));
        assertEquals(2.0, frame.avg(2));
        assertEquals(2.1, frame.min(2));
        assertEquals(2.2, frame.max(2));
        assertEquals(2.3, frame.sum(2));
    }

    @Test
    void aReadsFramesAreAnIngestBody() throws Exception {
        // The response as a read answers it, end marker included, posted back as it is.
        byte[] stream = ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT), List.of(floatBatch(1, 0, 10)), ZSTD);
        byte[] response = relay(DatapointValueType.FLOAT, false, 1, stream);
        List<DatapointFrame> ingested = DatapointFrame.parseAll(response, ZSTD);
        assertEquals(1, ingested.size());
        assertEquals(10, ingested.get(0).rowCount());
        assertEquals(1, ingested.get(0).runs().size());
        assertEquals("s1", ingested.get(0).runs().get(0).externalId());
    }

    @Test
    void aSliceKeepsEveryRowOfAMixedBatchAtAnUnalignedOffset() throws Exception {
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
                relay(DatapointValueType.MIXED, false, 9, ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.MIXED), List.of(sliced), ZSTD)), ZSTD).get(0);
        assertEquals(8, part.rowCount());
        for (int r = 0; r < 8; r++) {
            assertEquals(whole.timestamp(3 + r), part.timestamp(r), "row " + r);
            assertEquals(whole.valueAsString(3 + r), part.valueAsString(r), "row " + r);
        }
        assertTrue(part.valueAsString(0).startsWith("FAULT"), part.valueAsString(0));
    }

    @Test
    void aSliceOfANarrowColumnIsTheRowsAsked() throws Exception {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(5, "s5");
        for (int r = 0; r < 5; r++) {
            w.addFloat32(5, T0 + r, r + 0.5f);
        }
        DatapointFrame whole = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
        BatchData sliced = whole.batches().get(0).slice(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), 1, 4);
        DatapointFrame part = DatapointFrame.parseAll(
                relay(DatapointValueType.FLOAT32, false, 5, ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), List.of(sliced), ZSTD)), ZSTD).get(0);
        assertEquals(3, part.rowCount());
        for (int r = 0; r < 3; r++) {
            assertEquals(whole.valueAsString(1 + r), part.valueAsString(r), "row " + r);
            assertEquals(T0 + 1 + r, part.timestamp(r));
        }
    }
}
