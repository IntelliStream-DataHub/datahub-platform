// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnData;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every way a frame can be wrong, and the reason the reader names for it. The API turns the reason
 * into its problem response, so these are part of the contract.
 */
class DatapointFrameRejectionTest {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);

    private static byte[] goodFrame() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(3, "abc").series(9, "z");
        w.addFloat32(3, 1000, 1f);
        w.addFloat32(3, 2000, 2f);
        w.addFloat32(9, 1000, 3f);
        return w.build(ZSTD);
    }

    private static Reason reject(byte[] body) {
        FrameFormatException e = assertThrows(FrameFormatException.class, () -> DatapointFrame.parseAll(body, ZSTD));
        return e.reason();
    }

    private static byte[] tampered(Consumer<ByteBuffer> edit) {
        byte[] frame = goodFrame();
        edit.accept(ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN));
        return frame;
    }

    @Test
    void envelopeFields() {
        assertEquals(Reason.MALFORMED_FRAME, reject(new byte[0]));
        assertEquals(Reason.MALFORMED_FRAME, reject(new byte[10]));
        assertEquals(Reason.MALFORMED_FRAME, reject(tampered(b -> b.put(0, (byte) 'X'))));
        assertEquals(Reason.UNSUPPORTED_VERSION, reject(tampered(b -> b.put(4, (byte) 2))));
        assertEquals(Reason.UNKNOWN_VALUE_TYPE, reject(tampered(b -> b.put(5, (byte) 8))));
        assertEquals(Reason.UNSUPPORTED_CODEC, reject(tampered(b -> b.put(6, (byte) 0))));
        assertEquals(Reason.UNCOMPRESSED_FRAME, reject(tampered(b -> b.put(7, (byte) 0))));
        assertEquals(Reason.MALFORMED_FRAME, reject(tampered(b -> b.put(7, (byte) 2))));
        assertEquals(Reason.ROW_COUNT_MISMATCH, reject(tampered(b -> b.putInt(8, 0))));
        assertEquals(Reason.ROW_COUNT_MISMATCH, reject(tampered(b -> b.putInt(8, 100_001))));
        assertEquals(Reason.DIRECTORY_INVALID, reject(tampered(b -> b.putInt(12, 0))));
        assertEquals(Reason.DIRECTORY_INVALID, reject(tampered(b -> b.putInt(12, 4))));
        assertEquals(Reason.MALFORMED_FRAME, reject(tampered(b -> b.putInt(20, 1 << 30))));
        assertEquals(Reason.FRAME_TOO_LARGE, reject(tampered(b -> b.putInt(24, FrameLimits.MAX_FRAME_RAW_BYTES + 1))));
        assertEquals(Reason.PAYLOAD_INVALID, reject(tampered(b -> b.putInt(24, b.getInt(24) + 1))));
    }

    @Test
    void textFramesHaveTheTighterRowCap() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(1, "a");
        for (int i = 0; i <= 10_000; i++) {
            w.addText(1, i, "x");
        }
        assertThrows(IllegalStateException.class, () -> w.build(ZSTD));
    }

    @Test
    void trailingBytesAndTooManyFrames() {
        byte[] frame = goodFrame();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(frame);
        body.write(0);
        assertEquals(Reason.MALFORMED_FRAME, reject(body.toByteArray()));

        ByteArrayOutputStream many = new ByteArrayOutputStream();
        for (int i = 0; i <= FrameLimits.MAX_FRAMES_PER_REQUEST; i++) {
            many.writeBytes(frame);
        }
        assertEquals(Reason.TOO_MANY_FRAMES, reject(many.toByteArray()));
    }

    @Test
    void directory() {
        // Swap the two ids so they are no longer ascending.
        assertEquals(Reason.DIRECTORY_INVALID, reject(tampered(b -> b.putLong(28, 99))));
        // Claim a different id than the payload holds.
        assertEquals(Reason.DIRECTORY_INVALID, reject(tampered(b -> b.putLong(28, 2))));
        // Break the external id length.
        assertEquals(Reason.DIRECTORY_INVALID, reject(tampered(b -> b.put(36, (byte) 200))));
        // Invalid UTF-8 in an external id.
        assertEquals(Reason.DIRECTORY_INVALID, reject(tampered(b -> b.put(37, (byte) 0xFF))));
    }

    @Test
    void payloadAndOrder() {
        // A corrupt zstd stream.
        assertEquals(Reason.PAYLOAD_INVALID, reject(tampered(b -> b.put(b.limit() - 3, (byte) 0x55))));
        // A stream of the wrong schema: FLOAT data under a FLOAT32 envelope.
        assertEquals(Reason.SCHEMA_MISMATCH, reject(withStream(DatapointValueType.FLOAT32, 3, stream(DatapointValueType.FLOAT,
                new long[]{3, 3, 9}, new long[]{1000, 2000, 1000}, doubles(1, 2, 3)))));
        // Unsorted rows and a repeated pair.
        assertEquals(Reason.UNSORTED, reject(withStream(DatapointValueType.FLOAT, 3, stream(DatapointValueType.FLOAT,
                new long[]{9, 3, 3}, new long[]{1000, 1000, 2000}, doubles(1, 2, 3)))));
        assertEquals(Reason.UNSORTED, reject(withStream(DatapointValueType.FLOAT, 3, stream(DatapointValueType.FLOAT,
                new long[]{3, 3, 9}, new long[]{2000, 1000, 1000}, doubles(1, 2, 3)))));
        assertEquals(Reason.DUPLICATE_ROW, reject(withStream(DatapointValueType.FLOAT, 3, stream(DatapointValueType.FLOAT,
                new long[]{3, 3, 9}, new long[]{1000, 1000, 1000}, doubles(1, 2, 3)))));
        // Envelope row count disagrees with the payload.
        assertEquals(Reason.ROW_COUNT_MISMATCH, reject(withStream(DatapointValueType.FLOAT, 2, stream(DatapointValueType.FLOAT,
                new long[]{3, 3, 9}, new long[]{1000, 2000, 1000}, doubles(1, 2, 3)))));
        // A series the directory does not list.
        assertEquals(Reason.DIRECTORY_INVALID, reject(withStream(DatapointValueType.FLOAT, 3, stream(DatapointValueType.FLOAT,
                new long[]{3, 3, 4}, new long[]{1000, 2000, 1000}, doubles(1, 2, 3)))));
        // Not an IPC stream at all.
        assertEquals(Reason.PAYLOAD_INVALID, reject(withStream(DatapointValueType.FLOAT, 3, new byte[]{1, 2, 3, 4, 5, 6, 7, 8})));
        // A stream with no end-of-stream marker.
        byte[] stream = stream(DatapointValueType.FLOAT, new long[]{3, 3, 9}, new long[]{1000, 2000, 1000}, doubles(1, 2, 3));
        byte[] cut = java.util.Arrays.copyOf(stream, stream.length - 8);
        assertEquals(Reason.PAYLOAD_INVALID, reject(withStream(DatapointValueType.FLOAT, 3, cut)));
        // Bytes after the marker.
        byte[] extra = java.util.Arrays.copyOf(stream, stream.length + 8);
        assertEquals(Reason.TRAILING_BYTES, reject(withStream(DatapointValueType.FLOAT, 3, extra)));
    }

    @Test
    void values() {
        // A decimal the column cannot hold: 10^18 unscaled.
        ByteBuffer big = ByteBuffer.allocate(16 * 3).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 3; i++) {
            big.putLong(1_000_000_000_000_000_000L).putLong(0);
        }
        big.flip();
        assertEquals(Reason.VALUE_INVALID, reject(withStream(DatapointValueType.NUMERIC, 3, streamWithValue(DatapointValueType.NUMERIC,
                new long[]{3, 3, 9}, new long[]{1000, 2000, 1000}, new ColumnData(null, 0, null, big)))));
        // A high word that is not the sign extension of the low word.
        ByteBuffer bad = ByteBuffer.allocate(16 * 3).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 3; i++) {
            bad.putLong(5).putLong(1);
        }
        bad.flip();
        assertEquals(Reason.VALUE_INVALID, reject(withStream(DatapointValueType.NUMERIC, 3, streamWithValue(DatapointValueType.NUMERIC,
                new long[]{3, 3, 9}, new long[]{1000, 2000, 1000}, new ColumnData(null, 0, null, bad)))));
        // Text over 256 bytes, and text that is not UTF-8.
        assertEquals(Reason.VALUE_INVALID, reject(withStream(DatapointValueType.TEXT, 3, streamWithValue(DatapointValueType.TEXT,
                new long[]{3, 3, 9}, new long[]{1000, 2000, 1000}, utf8("a", "b".repeat(300), "c")))));
        ColumnData notUtf8 = utf8("a", "bb", "c");
        notUtf8.data().put(1, (byte) 0xFF);
        assertEquals(Reason.VALUE_INVALID, reject(withStream(DatapointValueType.TEXT, 3, streamWithValue(DatapointValueType.TEXT,
                new long[]{3, 3, 9}, new long[]{1000, 2000, 1000}, notUtf8))));
        // A mixed row with both columns set.
        byte[] all = {(byte) 0x07};
        ByteBuffer nums = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putDouble(1).putDouble(2).putDouble(3).flip();
        ColumnData numeric = new ColumnData(ByteBuffer.wrap(all), 0, null, nums);
        ColumnData text = utf8("a", "b", "c");
        ColumnData textSet = new ColumnData(ByteBuffer.wrap(all), 0, text.offsets(), text.data());
        byte[] mixed = ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.MIXED), List.of(new BatchData(3,
                new ColumnData[]{longs(3, 3, 9), longs(1000, 2000, 1000), numeric, textSet})));
        assertEquals(Reason.VALUE_INVALID, reject(withStream(DatapointValueType.MIXED, 3, mixed)));
    }

    // --- helpers that build a frame around an arbitrary payload ---

    static byte[] withStream(DatapointValueType type, int rows, byte[] rawStream) {
        byte[] compressed = ZSTD.compress(rawStream);
        byte[] dirA = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] dirB = "z".getBytes(StandardCharsets.UTF_8);
        int dirLen = 8 + 1 + dirA.length + 8 + 1 + dirB.length;
        ByteBuffer f = ByteBuffer.allocate(FrameLimits.HEADER_BYTES + dirLen + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        f.put(FrameLimits.MAGIC).put((byte) 1).put((byte) type.id()).put((byte) 1).put((byte) 1);
        f.putInt(rows).putInt(2).putInt(dirLen).putInt(compressed.length).putInt(rawStream.length);
        f.putLong(3).put((byte) dirA.length).put(dirA);
        f.putLong(9).put((byte) dirB.length).put(dirB);
        f.put(compressed);
        return f.array();
    }

    static byte[] stream(DatapointValueType type, long[] ids, long[] ts, ColumnData value) {
        return streamWithValue(type, ids, ts, value);
    }

    static byte[] streamWithValue(DatapointValueType type, long[] ids, long[] ts, ColumnData value) {
        return ArrowIpc.writeStream(ArrowSchemaCanon.of(type), List.of(new BatchData(ids.length,
                new ColumnData[]{longs(ids), longs(ts), value})));
    }

    static ColumnData longs(long... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) b.putLong(v);
        b.flip();
        return new ColumnData(null, 0, null, b);
    }

    static ColumnData doubles(double... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) b.putDouble(v);
        b.flip();
        return new ColumnData(null, 0, null, b);
    }

    static ColumnData utf8(String... values) {
        ByteBuffer offsets = ByteBuffer.allocate((values.length + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        offsets.putInt(0);
        for (String v : values) {
            data.writeBytes(v.getBytes(StandardCharsets.UTF_8));
            offsets.putInt(data.size());
        }
        offsets.flip();
        return new ColumnData(null, 0, offsets, ByteBuffer.wrap(data.toByteArray()));
    }
}
