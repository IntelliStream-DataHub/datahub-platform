// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The consumer's relay: a frame's record batches reach the insert stream as the client sent them,
 * the in-band compressed ones byte for byte, a whole-payload frame's decompressed once and
 * otherwise untouched, and the stream of several frames keeps their batches apart.
 */
class FrameMergerTest {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);
    static final byte[] END_OF_STREAM = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    private static DatapointFrame inBand(DatapointValueType type, long id, String... values) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type).series(id, "s" + id);
        for (int r = 0; r < values.length; r++) {
            w.add(id, r, values[r]);
        }
        return DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
    }

    /** The same rows as a {@link FrameLimits#COMPRESSION_ZSTD} frame: the whole stream zstd, its buffers plain. */
    private static DatapointFrame wholePayload(DatapointFrame inBand) {
        byte[] compressed = ZSTD.compress(plainStream(inBand));
        long[] ids = inBand.seriesIds();
        String[] names = inBand.externalIds();
        int directoryLength = 0;
        for (String n : names) {
            directoryLength += 8 + 1 + n.getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer f = ByteBuffer.allocate(FrameLimits.HEADER_BYTES + directoryLength + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        f.put(FrameLimits.MAGIC).put((byte) FrameLimits.VERSION).put((byte) inBand.valueType().id())
                .put((byte) FrameLimits.CODEC_ARROW_IPC).put((byte) FrameLimits.COMPRESSION_ZSTD);
        f.putInt(inBand.rowCount()).putInt(ids.length).putInt(directoryLength).putInt(compressed.length).putInt(plainStream(inBand).length);
        for (int s = 0; s < ids.length; s++) {
            byte[] name = names[s].getBytes(StandardCharsets.UTF_8);
            f.putLong(ids[s]).put((byte) name.length).put(name);
        }
        f.put(compressed);
        return DatapointFrame.parseAll(f.array(), ZSTD).get(0);
    }

    /** The frame's stream with its buffers uncompressed. */
    private static byte[] plainStream(DatapointFrame frame) {
        return ArrowIpc.writeStream(ArrowSchemaCanon.of(frame.valueType()), frame.batches().stream().map(Batch::toData).toList());
    }

    /** The record batch messages of a stream: what lies between its schema message and its end marker. */
    private static byte[] batchMessages(DatapointValueType type, byte[] stream) {
        int schema = ArrowSchemaCanon.of(type).schemaMessage().length;
        return Arrays.copyOfRange(stream, schema, stream.length - END_OF_STREAM.length);
    }

    private static List<Batch> read(DatapointValueType type, byte[] stream) throws IOException {
        ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(ArrowSchemaCanon.of(type), new ByteArrayInputStream(stream),
                0, 1_000_000, 1 << 30, ZSTD);
        List<Batch> batches = new ArrayList<>();
        for (Batch b = reader.next(); b != null; b = reader.next()) {
            batches.add(b);
        }
        return batches;
    }

    @Test
    void relaysAnInBandFrameByteForByte() throws IOException {
        DatapointFrame f = inBand(DatapointValueType.FLOAT32, 1, "1.5", "2.5", "3.5");
        assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, f.compression());

        byte[] batches = FrameMerger.batches(f, ZSTD);
        assertArrayEquals(batchMessages(DatapointValueType.FLOAT32, f.payloadBytes()), batches);

        byte[] stream = FrameMerger.stream(DatapointValueType.FLOAT32, List.of(batches));
        assertArrayEquals(f.payloadBytes(), stream);
        Batch only = read(DatapointValueType.FLOAT32, stream).get(0);
        assertEquals(3, only.rows());
        assertEquals(3.5f, only.columns()[2].data().getFloat(2 * 4));
    }

    @Test
    void decompressesAWholePayloadFrameOnceAndRelaysItsPlainBatches() throws IOException {
        DatapointFrame f = wholePayload(inBand(DatapointValueType.MIXED, 2, "open", "1.5", "closed"));
        assertEquals(FrameLimits.COMPRESSION_ZSTD, f.compression());

        byte[] batches = FrameMerger.batches(f, ZSTD);
        assertArrayEquals(batchMessages(DatapointValueType.MIXED, plainStream(f)), batches);

        Batch only = read(DatapointValueType.MIXED, FrameMerger.stream(DatapointValueType.MIXED, List.of(batches))).get(0);
        assertEquals(3, only.rows());
        assertEquals(2, only.columns()[2].nullCount());
        assertEquals(1.5, only.columns()[2].data().getDouble(8));
        assertEquals("closed", only.columns()[3].utf8At(2));
    }

    @Test
    void aStreamOfSeveralFramesKeepsTheirBatchesApart() throws IOException {
        List<DatapointFrame> frames = List.of(
                inBand(DatapointValueType.TEXT, 1, "first", "second value"),
                wholePayload(inBand(DatapointValueType.TEXT, 2, "æøå")),
                inBand(DatapointValueType.TEXT, 3, "x", "y", "z"));
        List<byte[]> batches = frames.stream().map(f -> FrameMerger.batches(f, ZSTD)).toList();

        List<Batch> read = read(DatapointValueType.TEXT, FrameMerger.stream(DatapointValueType.TEXT, batches));
        assertEquals(List.of(2, 1, 3), read.stream().map(Batch::rows).toList());
        assertEquals("second value", read.get(0).columns()[2].utf8At(1));
        assertEquals("æøå", read.get(1).columns()[2].utf8At(0));
        assertEquals(2L, read.get(1).columns()[0].data().getLong(0));
        assertEquals("z", read.get(2).columns()[2].utf8At(2));
    }

    @Test
    void aFrameWhoseStreamIsNotWellFormedIsNamed() {
        DatapointFrame good = inBand(DatapointValueType.BIGINT, 7, "1", "2");
        byte[] bytes = good.frameBytes();
        // The record batch message follows the schema message; its continuation marker is first.
        int batch = bytes.length - good.payloadLength() + ArrowSchemaCanon.of(DatapointValueType.BIGINT).schemaMessage().length;
        bytes[batch] = 0;
        DatapointFrame broken = DatapointFrame.parseEnvelopes(bytes).get(0);

        FrameFormatException e = assertThrows(FrameFormatException.class, () -> FrameMerger.batches(broken, ZSTD));
        assertEquals(FrameFormatException.Reason.PAYLOAD_INVALID, e.reason());
    }
}
