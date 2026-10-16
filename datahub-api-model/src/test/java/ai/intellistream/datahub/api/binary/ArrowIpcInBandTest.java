// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnData;
import ai.intellistream.datahub.api.binary.ArrowIpc.RawBatch;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;
import com.google.flatbuffers.FlatBufferBuilder;
import org.apache.arrow.flatbuf.BodyCompression;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.CompressionType;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.MetadataVersion;
import org.apache.arrow.flatbuf.RecordBatch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Record batches whose buffers are compressed in-band, Arrow's body compression: written by
 * {@link ArrowIpc#writeStream(ArrowSchemaCanon, List, PayloadCodec)} and read back by
 * {@link ArrowIpc#readCompressedStream}, in the layout ClickHouse and pyarrow use, and refused
 * where the contract says a stream must or must not carry it.
 */
class ArrowIpcInBandTest {

    private static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);

    /** A stream with every record batch compressed in-band, as ClickHouse answers the read's query. */
    private static byte[] inBand(ArrowSchemaCanon canon, List<BatchData> batches) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ArrowIpc.writeStream(canon, batches, out, ZSTD);
        return out.toByteArray();
    }
    /** Compresses nothing, so the writer stores every buffer as it is behind a -1 prefix. */
    private static final PayloadCodec NONE = new PayloadCodec() {
        @Override
        public byte[] compress(byte[] raw) {
            return raw;
        }

        @Override
        public byte[] decompress(byte[] compressed, int rawLength) {
            throw new IllegalStateException("a stored buffer is not decompressed");
        }
    };

    private static final ArrowSchemaCanon MIXED = ArrowSchemaCanon.of(DatapointValueType.MIXED);

    /** A mixed batch: numbers and text in runs, so both value columns carry a validity bitmap. */
    private static List<BatchData> mixedBatches(int rows) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.MIXED).series(1, "m");
        for (int r = 0; r < rows; r++) {
            w.addMixed(1, 1_700_000_000_000L + r, (r / 3) % 2 == 0 ? Double.toString(r / 7.0) : "state-" + r);
        }
        return List.of(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0).batches().get(0).toData());
    }

    /** What the batches' bodies add up to uncompressed: the raw length a compression-2 frame declares. */
    private static long rawBodyBytes(ArrowSchemaCanon canon, byte[] stream, PayloadCodec codec) throws IOException {
        ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(canon, new ByteArrayInputStream(stream), 0, Integer.MAX_VALUE, 1 << 30, codec);
        long total = 0;
        for (RawBatch raw = reader.nextRaw(); raw != null; raw = reader.nextRaw()) {
            total += raw.rawBodyBytes();
        }
        return total;
    }

    private static void assertSameValues(List<Batch> expected, List<Batch> actual) {
        assertEquals(expected.size(), actual.size());
        for (int b = 0; b < expected.size(); b++) {
            Batch e = expected.get(b);
            Batch a = actual.get(b);
            assertEquals(e.rows(), a.rows());
            for (int c = 0; c < e.columns().length; c++) {
                assertEquals(e.columns()[c].nullCount(), a.columns()[c].nullCount(), "column " + c + " nulls");
                assertEquals(e.columns()[c].data(), a.columns()[c].data(), "column " + c + " data");
                assertEquals(e.columns()[c].offsets(), a.columns()[c].offsets(), "column " + c + " offsets");
                if (e.columns()[c].nullCount() > 0) {
                    assertEquals(e.columns()[c].validity(), a.columns()[c].validity(), "column " + c + " validity");
                }
            }
        }
    }

    @Test
    void buffersCompressedInBandReadBackAsWritten() throws IOException {
        List<BatchData> batches = mixedBatches(3000);
        byte[] plain = ArrowIpc.writeStream(MIXED, batches);
        byte[] inBand = inBand(MIXED, batches);
        assertTrue(inBand.length < plain.length / 2, inBand.length + " bytes in-band against " + plain.length);

        long rawLength = rawBodyBytes(MIXED, plain, null);
        assertEquals(rawLength, rawBodyBytes(MIXED, inBand, ZSTD), "the prefixes say what the uncompressed bodies measure");
        List<Batch> read = ArrowIpc.readCompressedStream(MIXED, inBand, 0, 10_000, ZSTD, (int) rawLength);
        assertSameValues(ArrowIpc.readStream(MIXED, plain, 0, 10_000), read);
    }

    @Test
    void aBufferStoredBehindAMinusOnePrefixIsTakenAsItIs() throws IOException {
        List<BatchData> batches = mixedBatches(50);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        ArrowIpc.writeStream(MIXED, batches, stored, NONE);
        byte[] stream = stored.toByteArray();
        // The first non-empty buffer of the first batch starts with the -1 prefix.
        ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(MIXED, new ByteArrayInputStream(stream), 0, 100, 1 << 20, NONE);
        RawBatch raw = reader.nextRaw();
        assertTrue(raw.compressed());
        Message message = Message.getRootAsMessage(ByteBuffer.wrap(raw.metadata()).order(ByteOrder.LITTLE_ENDIAN));
        RecordBatch rb = (RecordBatch) message.header(new RecordBatch());
        Buffer first = rb.buffers(1);
        assertEquals(-1L, ByteBuffer.wrap(raw.body()).order(ByteOrder.LITTLE_ENDIAN).getLong((int) first.offset()));
        assertEquals(0, rb.buffers(0).length(), "the id column has no nulls and so no validity bitmap");

        long rawLength = rawBodyBytes(MIXED, ArrowIpc.writeStream(MIXED, batches), null);
        assertSameValues(ArrowIpc.readStream(MIXED, ArrowIpc.writeStream(MIXED, batches), 0, 100),
                ArrowIpc.readCompressedStream(MIXED, stream, 0, 100, NONE, (int) rawLength));
    }

    @Test
    void aWholePayloadStreamMustNotCarryInBandCompression() {
        byte[] inBand = inBand(MIXED, mixedBatches(5));
        FrameFormatException e = assertThrows(FrameFormatException.class, () -> ArrowIpc.readStream(MIXED, inBand, 0, 100));
        assertEquals(Reason.PAYLOAD_INVALID, e.reason());
        assertTrue(e.getMessage().contains("must not be compressed"), e.getMessage());
    }

    @Test
    void anInBandPayloadMustCompressEveryBatch() {
        byte[] plain = ArrowIpc.writeStream(MIXED, mixedBatches(5));
        FrameFormatException e = assertThrows(FrameFormatException.class, () -> ArrowIpc.readCompressedStream(MIXED, plain, 0, 100, ZSTD, 1));
        assertEquals(Reason.PAYLOAD_INVALID, e.reason());
        assertTrue(e.getMessage().contains("must be compressed in-band"), e.getMessage());
    }

    @Test
    void theRawLengthMustBeWhatTheBuffersDecompressTo() throws IOException {
        byte[] inBand = inBand(MIXED, mixedBatches(5));
        int rawLength = (int) rawBodyBytes(MIXED, inBand, ZSTD);
        assertEquals(5, ArrowIpc.readCompressedStream(MIXED, inBand, 0, 100, ZSTD, rawLength).get(0).rows());
        FrameFormatException e = assertThrows(FrameFormatException.class, () -> ArrowIpc.readCompressedStream(MIXED, inBand, 0, 100, ZSTD, rawLength + 8));
        assertEquals(Reason.PAYLOAD_INVALID, e.reason());
        assertTrue(e.getMessage().contains("decompress to"), e.getMessage());
        // A declared length past the frame's raw length is refused before anything is allocated.
        FrameFormatException over = assertThrows(FrameFormatException.class, () -> ArrowIpc.readCompressedStream(MIXED, inBand, 0, 100, ZSTD, 16));
        assertEquals(Reason.PAYLOAD_INVALID, over.reason());
        assertTrue(over.getMessage().contains("declares"), over.getMessage());
    }

    @Test
    void theStreamReaderDecodesInBandBatchesOnlyWhenGivenTheCodec() throws IOException {
        List<BatchData> batches = mixedBatches(20);
        byte[] inBand = inBand(MIXED, batches);
        ArrowIpc.StreamReader without = new ArrowIpc.StreamReader(MIXED, new ByteArrayInputStream(inBand), 0, 100, 1 << 20);
        FrameFormatException e = assertThrows(FrameFormatException.class, without::next);
        assertEquals(Reason.PAYLOAD_INVALID, e.reason());

        ArrowIpc.StreamReader with = new ArrowIpc.StreamReader(MIXED, new ByteArrayInputStream(inBand), 0, 100, 1 << 20, ZSTD);
        RawBatch raw = with.nextRaw();
        assertTrue(raw.compressed());
        assertEquals(20, raw.rows());
        assertSameValues(ArrowIpc.readStream(MIXED, ArrowIpc.writeStream(MIXED, batches), 0, 100), List.of(with.decode(raw)));
        assertEquals(null, with.nextRaw());

        // An uncompressed batch is still read, and says so.
        ArrowIpc.StreamReader plain = new ArrowIpc.StreamReader(MIXED, new ByteArrayInputStream(ArrowIpc.writeStream(MIXED, batches)), 0, 100, 1 << 20, ZSTD);
        RawBatch uncompressed = plain.nextRaw();
        assertFalse(uncompressed.compressed());
        assertEquals(uncompressed.body().length, uncompressed.rawBodyBytes());
    }

    // --- hand-built record batch messages, for what no writer of ours produces ---

    private static final ArrowSchemaCanon FLOAT32 = ArrowSchemaCanon.of(DatapointValueType.FLOAT32);

    /** A FLOAT32 stream around one hand-built record batch of {@code rows} rows with the given buffers. */
    private static byte[] streamAround(int rows, long[][] buffers, byte[] body, byte codec, byte method) {
        FlatBufferBuilder fb = new FlatBufferBuilder(256);
        RecordBatch.startNodesVector(fb, 3);
        for (int i = 2; i >= 0; i--) {
            FieldNode.createFieldNode(fb, rows, 0);
        }
        int nodes = fb.endVector();
        RecordBatch.startBuffersVector(fb, buffers.length);
        for (int i = buffers.length - 1; i >= 0; i--) {
            Buffer.createBuffer(fb, buffers[i][0], buffers[i][1]);
        }
        int bufferVector = fb.endVector();
        int compression = BodyCompression.createBodyCompression(fb, codec, method);
        int recordBatch = RecordBatch.createRecordBatch(fb, rows, nodes, bufferVector, compression, 0);
        fb.finish(Message.createMessage(fb, MetadataVersion.V5, MessageHeader.RecordBatch, recordBatch, body.length, 0));
        byte[] schemaOnly = ArrowIpc.writeStream(FLOAT32, List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(schemaOnly, 0, schemaOnly.length - 8);
        out.writeBytes(ArrowIpc.encapsulate(fb.sizedByteArray()));
        out.writeBytes(body);
        out.write(schemaOnly, schemaOnly.length - 8, 8);
        return out.toByteArray();
    }

    /** A body of three stored buffers for two FLOAT32 rows: ids, timestamps, values, each -1 and the bytes. */
    private static byte[] storedBody() {
        ByteBuffer body = ByteBuffer.allocate(24 + 24 + 16).order(ByteOrder.LITTLE_ENDIAN);
        body.putLong(-1).putLong(1).putLong(1);
        body.putLong(-1).putLong(1000).putLong(2000);
        body.putLong(-1).putFloat(1.5f).putFloat(2.5f);
        return body.array();
    }

    private static final long[][] STORED_BUFFERS = {{0, 0}, {0, 24}, {24, 0}, {24, 24}, {48, 0}, {48, 16}};

    private static FrameFormatException refused(byte[] stream) {
        return assertThrows(FrameFormatException.class, () -> ArrowIpc.readCompressedStream(FLOAT32, stream, 0, 10, ZSTD, 64));
    }

    @Test
    void handBuiltStoredBuffersReadBack() {
        byte[] stream = streamAround(2, STORED_BUFFERS, storedBody(), CompressionType.ZSTD, (byte) 0);
        // Two int64 ids, two int64 timestamps, two float32 values: 16, 16 and 8 bytes uncompressed.
        Batch batch = ArrowIpc.readCompressedStream(FLOAT32, stream, 0, 10, ZSTD, 16 + 16 + 8).get(0);
        assertEquals(2, batch.rows());
        assertEquals(2000, batch.columns()[1].data().getLong(8));
        assertEquals(2.5f, batch.columns()[2].data().getFloat(4));
    }

    @Test
    void onlyZstdByBufferIsAccepted() {
        FrameFormatException lz4 = refused(streamAround(2, STORED_BUFFERS, storedBody(), CompressionType.LZ4_FRAME, (byte) 0));
        assertEquals(Reason.PAYLOAD_INVALID, lz4.reason());
        assertTrue(lz4.getMessage().contains("not zstd"), lz4.getMessage());
        FrameFormatException method = refused(streamAround(2, STORED_BUFFERS, storedBody(), CompressionType.ZSTD, (byte) 1));
        assertEquals(Reason.PAYLOAD_INVALID, method.reason());
        assertTrue(method.getMessage().contains("not by buffer"), method.getMessage());
    }

    @Test
    void aBufferTooShortForItsPrefixIsRefused() {
        long[][] buffers = {{0, 0}, {0, 24}, {24, 0}, {24, 24}, {48, 0}, {48, 4}};
        FrameFormatException e = refused(streamAround(2, buffers, storedBody(), CompressionType.ZSTD, (byte) 0));
        assertEquals(Reason.PAYLOAD_INVALID, e.reason());
        assertTrue(e.getMessage().contains("too short for its length prefix"), e.getMessage());
    }

    @Test
    void aDeclaredLengthOutsideWhatTheFrameAllowsIsRefused() {
        byte[] body = storedBody();
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putLong(0, 1L << 40);
        FrameFormatException huge = refused(streamAround(2, STORED_BUFFERS, body, CompressionType.ZSTD, (byte) 0));
        assertEquals(Reason.PAYLOAD_INVALID, huge.reason());
        assertTrue(huge.getMessage().contains("declares"), huge.getMessage());
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putLong(0, -2);
        FrameFormatException negative = refused(streamAround(2, STORED_BUFFERS, body, CompressionType.ZSTD, (byte) 0));
        assertEquals(Reason.PAYLOAD_INVALID, negative.reason());
    }

    @Test
    void aCorruptZstdBufferIsRefused() {
        ByteBuffer body = ByteBuffer.allocate(24 + 24 + 24).order(ByteOrder.LITTLE_ENDIAN);
        body.putLong(-1).putLong(1).putLong(1);
        body.putLong(-1).putLong(1000).putLong(2000);
        body.putLong(8).put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        long[][] buffers = {{0, 0}, {0, 24}, {24, 0}, {24, 24}, {48, 0}, {48, 24}};
        FrameFormatException e = refused(streamAround(2, buffers, body.array(), CompressionType.ZSTD, (byte) 0));
        assertEquals(Reason.PAYLOAD_INVALID, e.reason());
        assertTrue(e.getMessage().contains("buffer 5"), e.getMessage());
    }
}
