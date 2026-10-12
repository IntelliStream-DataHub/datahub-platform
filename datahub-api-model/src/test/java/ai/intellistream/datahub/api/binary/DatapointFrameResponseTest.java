// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnData;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frames a binary read answers with: relayed around record batches the server already has,
 * read back by {@link DatapointFrame#parseResponse}, which also takes aggregate frames and more
 * frames than a request may carry. Ingest takes the answer back as it is, end marker included,
 * and must still refuse an aggregate frame.
 */
class DatapointFrameResponseTest {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);

    private static ByteBuffer longs(long... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) b.putLong(v);
        return b.flip();
    }

    private static ByteBuffer doubles(double... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) b.putDouble(v);
        return b.flip();
    }

    private static ColumnData column(ByteBuffer data) {
        return new ColumnData(null, 0, null, data);
    }

    /** The frames the relay writes around {@code batches}: one, unless they exceed the row cap. */
    private static byte[] frames(DatapointValueType type, boolean aggregates, long id, String externalId, List<BatchData> batches) {
        ArrowSchemaCanon canon = aggregates ? ArrowSchemaCanon.aggregates() : ArrowSchemaCanon.of(type);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            DatapointFrameWriter.relayStream(type, aggregates, id, externalId,
                    new ByteArrayInputStream(ArrowIpc.writeStream(canon, batches, ZSTD)), ZSTD, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] aggregateFrame(long id, long... buckets) {
        double[] avg = new double[buckets.length];
        double[] min = new double[buckets.length];
        double[] max = new double[buckets.length];
        double[] sum = new double[buckets.length];
        long[] ids = new long[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            ids[i] = id;
            avg[i] = i + 0.5;
            min[i] = i;
            max[i] = i + 1;
            sum[i] = 10 * i;
        }
        BatchData batch = new BatchData(buckets.length, new ColumnData[]{
                column(longs(ids)), column(longs(buckets)),
                column(doubles(avg)), column(doubles(min)), column(doubles(max)), column(doubles(sum))});
        return frames(DatapointValueType.FLOAT, true, id, "agg" + id, List.of(batch));
    }

    private static byte[] response(byte[]... frames) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] f : frames) body.writeBytes(f);
        body.writeBytes(FrameLimits.END_OF_RESPONSE);
        return body.toByteArray();
    }

    /** Batches as the server holds them after checking ClickHouse's stream: read from another frame. */
    private static List<BatchData> batchesOf(byte[] frame) {
        return DatapointFrame.parseAll(frame, ZSTD).get(0).batches().stream().map(ArrowIpc.Batch::toData).toList();
    }

    @Test
    void relayedValueFramesReadBack() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.NUMERIC).series(7, "seven");
        w.add(7, 1000, "1.5");
        w.add(7, 2000, "-0.000001");
        byte[] frame = frames(DatapointValueType.NUMERIC, false, 7, "seven", batchesOf(w.build(ZSTD)));

        DatapointFrame read = DatapointFrame.parseResponse(response(frame), ZSTD).get(0);
        assertFalse(read.aggregates());
        assertArrayEquals(new long[]{7}, read.seriesIds());
        assertArrayEquals(new String[]{"seven"}, read.externalIds());
        assertEquals("1.500000", read.valueAsString(0));
        assertEquals("-0.000001", read.valueAsString(1));
        assertThrows(IllegalStateException.class, () -> read.avg(0));
        assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, read.compression());
        // A value frame is still a valid ingest frame, and so is the whole answer with its marker.
        assertEquals(2, DatapointFrame.parseAll(frame, ZSTD).get(0).rowCount());
        assertEquals(2, DatapointFrame.parseAll(response(frame), ZSTD).get(0).rowCount());
    }

    @Test
    void ingestTakesTheEndMarkerOnlyAfterAFrameAndNothingAfterIt() {
        assertEquals(Reason.MALFORMED_FRAME, assertThrows(FrameFormatException.class,
                () -> DatapointFrame.parseEnvelopes(response())).reason());
        byte[] frame = frames(DatapointValueType.FLOAT, false, 1, "f", batchesOf(floatFrame()));
        byte[] extended = Arrays.copyOf(response(frame), response(frame).length + 1);
        assertEquals(Reason.TRAILING_BYTES, assertThrows(FrameFormatException.class,
                () -> DatapointFrame.parseEnvelopes(extended)).reason());
    }

    private static byte[] floatFrame() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(1, "f");
        w.add(1, 1000, "1.5");
        return w.build(ZSTD);
    }

    @Test
    void aMixedColumnWithoutNullsNeedsNoValidityBitmap() {
        // Numbers only: value_text is all null, value_numeric has none, and its bitmap is left out.
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.MIXED).series(1, "m");
        w.add(1, 1000, "1.5");
        w.add(1, 2000, "2.5");
        List<BatchData> batches = batchesOf(w.build(ZSTD));
        assertEquals(0, batches.get(0).columns()[2].nullCount());

        byte[] frame = frames(DatapointValueType.MIXED, false, 1, "m", batches);
        DatapointFrame read = DatapointFrame.parseResponse(response(frame), ZSTD).get(0);
        assertEquals("1.5", read.valueAsString(0));
        assertEquals("2.5", read.valueAsString(1));
    }

    @Test
    void aggregateFramesReadBack() {
        DatapointFrame read = DatapointFrame.parseResponse(response(aggregateFrame(4, 3_600_000, 7_200_000)), ZSTD).get(0);
        assertTrue(read.aggregates());
        assertEquals(DatapointValueType.FLOAT, read.valueType());
        assertEquals(2, read.rowCount());
        assertEquals(7_200_000, read.timestamp(1));
        assertEquals(1.5, read.avg(1));
        assertEquals(1, read.min(1));
        assertEquals(2, read.max(1));
        assertEquals(10, read.sum(1));
        assertThrows(IllegalStateException.class, () -> read.valueAsString(0));
    }

    @Test
    void ingestRefusesAnAggregateFrame() {
        FrameFormatException e = assertThrows(FrameFormatException.class,
                () -> DatapointFrame.parseEnvelopes(aggregateFrame(4, 3_600_000)));
        assertEquals(Reason.UNSUPPORTED_CODEC, e.reason());
    }

    @Test
    void aResponseMayHoldMoreFramesThanARequestAndMayHoldNone() {
        int count = FrameLimits.MAX_FRAMES_PER_REQUEST + 1;
        byte[][] frames = new byte[count][];
        for (int i = 0; i < count; i++) {
            frames[i] = aggregateFrame(i, 3_600_000);
        }
        assertEquals(count, DatapointFrame.parseResponse(response(frames), ZSTD).size());
        assertTrue(DatapointFrame.parseResponse(response(), ZSTD).isEmpty());
    }

    @Test
    void aResponseCutShortIsDetectedWhereverItWasCut() {
        byte[] frame = aggregateFrame(4, 3_600_000);
        byte[] full = response(frame, frame);
        // Inside a frame, between two frames, inside the marker, and before anything at all.
        for (int length : new int[]{frame.length - 3, frame.length, full.length - 2, 0}) {
            byte[] cut = Arrays.copyOf(full, length);
            FrameFormatException e = assertThrows(FrameFormatException.class, () -> DatapointFrame.parseResponse(cut, ZSTD),
                    "cut at " + length);
            assertEquals(Reason.MALFORMED_FRAME, e.reason(), "cut at " + length);
        }
    }

    @Test
    void nothingMayFollowTheEndMarker() {
        byte[] trailing = response(aggregateFrame(4, 3_600_000));
        byte[] extended = Arrays.copyOf(trailing, trailing.length + 1);
        FrameFormatException e = assertThrows(FrameFormatException.class, () -> DatapointFrame.parseResponse(extended, ZSTD));
        assertEquals(Reason.TRAILING_BYTES, e.reason());
    }

    @Test
    void aBatchOverTheRowCapBecomesTwoFrames() {
        long[] buckets = new long[FrameLimits.MAX_AGGREGATE_ROWS + 1];
        for (int i = 0; i < buckets.length; i++) buckets[i] = i;
        List<DatapointFrame> frames = DatapointFrame.parseResponse(response(aggregateFrame(1, buckets)), ZSTD);
        assertEquals(List.of(FrameLimits.MAX_AGGREGATE_ROWS, 1), frames.stream().map(DatapointFrame::rowCount).toList());
        assertEquals(FrameLimits.MAX_AGGREGATE_ROWS, frames.get(1).timestamp(0));
    }
}
