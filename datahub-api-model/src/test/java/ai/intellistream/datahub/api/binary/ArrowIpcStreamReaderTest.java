// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;
import com.google.flatbuffers.FlatBufferBuilder;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArrowIpc.StreamReader} reads what {@link ArrowIpc#readStream} reads, one batch at a time
 * from a stream whose length nobody knows, and refuses the same things plus a declared size no
 * canon can carry.
 */
class ArrowIpcStreamReaderTest {

    private static final ArrowSchemaCanon CANON = ArrowSchemaCanon.of(DatapointValueType.FLOAT32);
    private static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);

    /** One stream of {@code batches} record batches of {@code rows} rows each. */
    private static byte[] stream(int batches, int rows) {
        List<BatchData> data = new ArrayList<>();
        for (int b = 0; b < batches; b++) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(1, "s");
            for (int r = 0; r < rows; r++) {
                w.addFloat32(1, 1_700_000_000_000L + (b * rows + r) * 1000L, r);
            }
            data.add(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0).batches().get(0).toData());
        }
        return ArrowIpc.writeStream(CANON, data);
    }

    private static List<Batch> readAll(byte[] stream, int maxRows) throws IOException {
        ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(CANON, new ByteArrayInputStream(stream), 0, maxRows);
        List<Batch> out = new ArrayList<>();
        for (Batch b = reader.next(); b != null; b = reader.next()) {
            out.add(b);
        }
        return out;
    }

    private static FrameFormatException rejected(byte[] stream, int maxRows) {
        return assertThrows(FrameFormatException.class, () -> readAll(stream, maxRows));
    }

    @Test
    void readsTheBatchesInOrderAndStopsAtTheEndMarker() throws IOException {
        byte[] stream = stream(3, 4);
        List<Batch> streamed = readAll(stream, 10);
        List<Batch> whole = ArrowIpc.readStream(CANON, stream, 0, 10);
        assertEquals(3, streamed.size());
        for (int b = 0; b < 3; b++) {
            assertEquals(4, streamed.get(b).rows());
            for (int c = 0; c < CANON.columns().size(); c++) {
                assertEquals(whole.get(b).columns()[c].data(), streamed.get(b).columns()[c].data(), "batch " + b + " column " + c);
            }
        }
    }

    @Test
    void aSchemaAloneIsNoBatch() throws IOException {
        assertTrue(readAll(ArrowIpc.writeStream(CANON, List.of()), 10).isEmpty());
    }

    @Test
    void aBatchOverTheRowCapIsRejected() {
        assertEquals(Reason.ROW_COUNT_MISMATCH, rejected(stream(1, 4), 3).reason());
    }

    @Test
    void aStreamCutShortIsAnErrorNotAShorterAnswer() {
        byte[] stream = stream(2, 4);
        // Inside the second batch's body; just before the end-of-stream marker; inside a prefix.
        for (int cut : new int[]{20, 8, 5}) {
            FrameFormatException e = rejected(Arrays.copyOf(stream, stream.length - cut), 10);
            assertEquals(Reason.PAYLOAD_INVALID, e.reason(), "cut " + cut);
            assertTrue(e.getMessage().contains("stream ends"), e.getMessage());
        }
    }

    @Test
    void bytesAfterTheEndMarkerAreRejected() {
        byte[] stream = stream(1, 2);
        assertEquals(Reason.TRAILING_BYTES, rejected(Arrays.copyOf(stream, stream.length + 1), 10).reason());
    }

    @Test
    void aMessageDeclaringMoreThanACanonCanCarryIsRefusedBeforeItIsRead() {
        // Metadata over the cap: the first message's length prefix, patched.
        byte[] stream = stream(1, 2);
        ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Integer.MAX_VALUE - 7);
        FrameFormatException meta = rejected(stream, 10);
        assertEquals(Reason.PAYLOAD_INVALID, meta.reason());
        assertTrue(meta.getMessage().contains("metadata bytes"), meta.getMessage());

        // A record batch whose message claims an 8 GiB body behind the canon's schema: refused on
        // the claim alone, before anything that size is asked for.
        FlatBufferBuilder fb = new FlatBufferBuilder(128);
        RecordBatch.startNodesVector(fb, 0);
        int nodes = fb.endVector();
        RecordBatch.startBuffersVector(fb, 0);
        int buffers = fb.endVector();
        int recordBatch = RecordBatch.createRecordBatch(fb, 2, nodes, buffers, 0, 0);
        fb.finish(Message.createMessage(fb, MetadataVersion.V5, MessageHeader.RecordBatch, recordBatch, 8L << 30, 0));
        byte[] schemaOnly = ArrowIpc.writeStream(CANON, List.of());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(schemaOnly, 0, schemaOnly.length - 8);
        body.writeBytes(ArrowIpc.encapsulate(fb.sizedByteArray()));
        body.write(schemaOnly, schemaOnly.length - 8, 8);
        FrameFormatException huge = rejected(body.toByteArray(), 10);
        assertEquals(Reason.PAYLOAD_INVALID, huge.reason());
        assertTrue(huge.getMessage().contains("body bytes"), huge.getMessage());
    }
}
