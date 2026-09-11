// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowSchemaCanon.Column;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon.Kind;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;
import com.google.flatbuffers.FlatBufferBuilder;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.MetadataVersion;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Arrow IPC stream format, restricted to what the datapoint contract allows. Reads and writes
 * with {@code arrow-format}'s generated FlatBuffers classes only, on heap, so no allocator and no
 * JVM flags.
 *
 * <p>Deliberately not {@code arrow-vector}, which owns this layer upstream. On Java 25 it fails in
 * class initialization unless the launch carries {@code --add-opens=java.base/java.nio=ALL-UNNAMED},
 * {@code --enable-native-access=ALL-UNNAMED}, {@code --sun-misc-unsafe-memory-access=allow} and
 * {@code -Dio.netty.tryReflectionSetAccessible=true}, and this module ships to SDK users, so those
 * flags would land in their launch configuration. It is also 14 jars against 2 (Netty and a second
 * Jackson generation among them), and it allocates off heap, which {@code -Xmx} does not bound for
 * the 64 MiB of untrusted body the api accepts per request. Its FlatBuffers verifier is off by
 * default in Java anyway, so it would not have checked these bytes for us: {@link #readStream}
 * rejects explicitly instead. The Rust SDK does use the full arrow-rs crates, which have neither
 * problem, and frames cross-parse between the two implementations.
 */
public final class ArrowIpc {

    private ArrowIpc() {
    }

    private static final int CONTINUATION = 0xFFFFFFFF;
    private static final byte[] END_OF_STREAM = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    /** One column of one record batch as little-endian views into the payload. */
    public record ColumnView(ByteBuffer validity, int nullCount, ByteBuffer offsets, ByteBuffer data) {

        public boolean isSet(int row) {
            if (nullCount == 0) {
                return true;
            }
            return (validity.get(row >>> 3) >>> (row & 7) & 1) != 0;
        }

        /** Start and end byte offsets of a Utf8 value. */
        public int offsetAt(int row) {
            return offsets.getInt(row * 4);
        }

        public String utf8At(int row) {
            int start = offsetAt(row);
            int end = offsetAt(row + 1);
            byte[] bytes = new byte[end - start];
            data.get(start, bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** One record batch of the canon: {@code rows} rows and one view per column. */
    public record Batch(int rows, ColumnView[] columns) {
    }

    /** The bytes of one column to write: the same shape as a view, from any source. */
    public record ColumnData(ByteBuffer validity, int nullCount, ByteBuffer offsets, ByteBuffer data) {
    }

    public record BatchData(int rows, ColumnData[] columns) {
    }

    static int pad8(int n) {
        return (n + 7) & ~7;
    }

    /** Wraps a finished FlatBuffer as an encapsulated IPC message: continuation, length, padding. */
    static byte[] encapsulate(byte[] flatbuffer) {
        int padded = pad8(flatbuffer.length);
        ByteBuffer out = ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(CONTINUATION);
        out.putInt(padded);
        out.put(flatbuffer);
        return out.array();
    }

    /** Writes one stream: the canon's schema, the batches in order, the end-of-stream marker. */
    public static byte[] writeStream(ArrowSchemaCanon canon, List<BatchData> batches) {
        int estimate = canon.schemaMessageLength() + 8;
        for (BatchData batch : batches) {
            estimate += 256;
            for (ColumnData c : batch.columns()) {
                estimate += pad8(c.data().remaining()) + 8;
                if (c.offsets() != null) estimate += pad8(c.offsets().remaining());
                if (c.validity() != null) estimate += pad8(c.validity().remaining());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(estimate);
        canon.writeSchemaMessage(out);
        for (BatchData batch : batches) {
            writeBatch(canon, batch, out);
        }
        out.write(END_OF_STREAM, 0, END_OF_STREAM.length);
        return out.toByteArray();
    }

    private static void writeBatch(ArrowSchemaCanon canon, BatchData batch, ByteArrayOutputStream out) {
        List<Column> columns = canon.columns();
        if (batch.columns().length != columns.size()) {
            throw new IllegalArgumentException("batch has " + batch.columns().length + " columns, canon has " + columns.size());
        }
        // Lay the body out first so the record batch metadata can point into it.
        int bufferCount = canon.bufferCount();
        long[] offsets = new long[bufferCount];
        long[] lengths = new long[bufferCount];
        ByteBuffer[] slices = new ByteBuffer[bufferCount];
        int b = 0;
        int position = 0;
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            ColumnData d = batch.columns()[i];
            ByteBuffer validity = c.nullable() ? requireNonNull(d.validity(), c.name() + " validity") : null;
            position = place(validity, position, offsets, lengths, slices, b++);
            if (c.kind() == Kind.UTF8) {
                position = place(requireNonNull(d.offsets(), c.name() + " offsets"), position, offsets, lengths, slices, b++);
            }
            position = place(requireNonNull(d.data(), c.name() + " data"), position, offsets, lengths, slices, b++);
        }
        int bodyLength = position;

        FlatBufferBuilder fb = new FlatBufferBuilder(128 + 32 * bufferCount);
        RecordBatch.startNodesVector(fb, columns.size());
        for (int i = columns.size() - 1; i >= 0; i--) {
            FieldNode.createFieldNode(fb, batch.rows(), batch.columns()[i].nullCount());
        }
        int nodes = fb.endVector();
        RecordBatch.startBuffersVector(fb, bufferCount);
        for (int i = bufferCount - 1; i >= 0; i--) {
            Buffer.createBuffer(fb, offsets[i], lengths[i]);
        }
        int buffers = fb.endVector();
        int recordBatch = RecordBatch.createRecordBatch(fb, batch.rows(), nodes, buffers, 0, 0);
        int message = Message.createMessage(fb, MetadataVersion.V5, MessageHeader.RecordBatch, recordBatch, bodyLength, 0);
        fb.finish(message);
        byte[] header = encapsulate(fb.sizedByteArray());
        out.write(header, 0, header.length);

        byte[] pad = new byte[8];
        for (int i = 0; i < bufferCount; i++) {
            ByteBuffer slice = slices[i];
            if (slice == null) {
                continue;
            }
            int n = slice.remaining();
            byte[] bytes = new byte[n];
            slice.duplicate().get(bytes);
            out.write(bytes, 0, n);
            out.write(pad, 0, pad8(n) - n);
        }
    }

    private static ByteBuffer requireNonNull(ByteBuffer buffer, String what) {
        if (buffer == null) {
            throw new IllegalArgumentException(what + " buffer is missing");
        }
        return buffer;
    }

    private static int place(ByteBuffer slice, int position, long[] offsets, long[] lengths, ByteBuffer[] slices, int index) {
        if (slice == null) {
            offsets[index] = position;
            lengths[index] = 0;
            return position;
        }
        int n = slice.remaining();
        offsets[index] = position;
        lengths[index] = n;
        slices[index] = slice;
        return position + pad8(n);
    }

    /**
     * Parses one stream against the canon and returns its batches as views. Every rejection in the
     * contract's list lands here, as a {@link FrameFormatException} naming the frame.
     */
    public static List<Batch> readStream(ArrowSchemaCanon canon, byte[] payload, int frameIndex, int maxRows) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        List<Batch> batches = new ArrayList<>();
        boolean schemaSeen = false;
        boolean ended = false;
        int pos = 0;
        while (!ended) {
            if (payload.length - pos < 8) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "stream ends without an end-of-stream marker");
            }
            if (buf.getInt(pos) != CONTINUATION) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message at " + pos + " lacks the continuation marker");
            }
            int metaLen = buf.getInt(pos + 4);
            if (metaLen == 0) {
                ended = true;
                pos += 8;
                break;
            }
            if (metaLen < 0 || (long) pos + 8 + metaLen > payload.length) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message at " + pos + " declares " + metaLen + " metadata bytes");
            }
            Message message;
            long bodyLength;
            try {
                message = Message.getRootAsMessage(buf.slice(pos + 8, metaLen).order(ByteOrder.LITTLE_ENDIAN));
                bodyLength = message.bodyLength();
            } catch (RuntimeException e) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message at " + pos + " is not a readable FlatBuffer");
            }
            if (message.version() < MetadataVersion.V4) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "metadata version " + message.version() + " is older than V4");
            }
            int bodyStart = pos + 8 + metaLen;
            if (bodyLength < 0 || bodyStart + bodyLength > payload.length) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message at " + pos + " declares " + bodyLength + " body bytes");
            }
            byte headerType = message.headerType();
            if (headerType == MessageHeader.Schema) {
                if (schemaSeen) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "more than one schema message");
                }
                if (bodyLength != 0) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "schema message carries a body");
                }
                Schema schema;
                String mismatch;
                try {
                    schema = (Schema) message.header(new Schema());
                    mismatch = schema == null ? "schema message has no schema" : canon.mismatch(schema);
                } catch (RuntimeException e) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "schema is not a readable FlatBuffer");
                }
                if (mismatch != null) {
                    throw new FrameFormatException(Reason.SCHEMA_MISMATCH, frameIndex, mismatch);
                }
                schemaSeen = true;
            } else if (headerType == MessageHeader.RecordBatch) {
                if (!schemaSeen) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch before the schema");
                }
                ByteBuffer body = buf.slice(bodyStart, (int) bodyLength).order(ByteOrder.LITTLE_ENDIAN);
                batches.add(readBatch(canon, message, body, frameIndex, maxRows));
            } else if (headerType == MessageHeader.DictionaryBatch) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "dictionary batches are not allowed");
            } else {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "unexpected message type " + headerType);
            }
            pos = (int) (bodyStart + bodyLength);
        }
        if (pos != payload.length) {
            throw new FrameFormatException(Reason.TRAILING_BYTES, frameIndex, (payload.length - pos) + " bytes after the end-of-stream marker");
        }
        if (batches.isEmpty()) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "no record batch");
        }
        return batches;
    }

    private static Batch readBatch(ArrowSchemaCanon canon, Message message, ByteBuffer body, int frameIndex, int maxRows) {
        RecordBatch rb;
        try {
            rb = (RecordBatch) message.header(new RecordBatch());
        } catch (RuntimeException e) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch is not a readable FlatBuffer");
        }
        if (rb == null) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch message has no record batch");
        }
        if (rb.compression() != null) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch bodies must not be compressed");
        }
        if (rb.variadicBufferCountsLength() != 0) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "variadic buffers are not allowed");
        }
        long rowsLong = rb.length();
        if (rowsLong < 1 || rowsLong > maxRows) {
            throw new FrameFormatException(Reason.ROW_COUNT_MISMATCH, frameIndex, "record batch has " + rowsLong + " rows, allowed 1 to " + maxRows);
        }
        int rows = (int) rowsLong;
        List<Column> columns = canon.columns();
        if (rb.nodesLength() != columns.size()) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch has " + rb.nodesLength() + " field nodes, expected " + columns.size());
        }
        if (rb.buffersLength() != canon.bufferCount()) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch has " + rb.buffersLength() + " buffers, expected " + canon.bufferCount());
        }
        ColumnView[] views = new ColumnView[columns.size()];
        int b = 0;
        int bitmapBytes = (rows + 7) >>> 3;
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            FieldNode node = rb.nodes(i);
            if (node.length() != rows) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " has " + node.length() + " rows, batch has " + rows);
            }
            long nullCount = node.nullCount();
            if (nullCount < 0 || nullCount > rows || (!c.nullable() && nullCount != 0)) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " declares " + nullCount + " nulls");
            }
            ByteBuffer validity = slice(body, rb.buffers(b++), frameIndex, c.name() + " validity");
            if (nullCount > 0 && validity.remaining() < bitmapBytes) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " validity bitmap is too short");
            }
            ByteBuffer offsets = null;
            ByteBuffer data;
            if (c.kind() == Kind.UTF8) {
                offsets = slice(body, rb.buffers(b++), frameIndex, c.name() + " offsets");
                data = slice(body, rb.buffers(b++), frameIndex, c.name() + " data");
                checkUtf8(c, rows, (int) nullCount, validity, offsets, data, frameIndex);
            } else {
                data = slice(body, rb.buffers(b++), frameIndex, c.name() + " data");
                if ((long) data.remaining() < (long) rows * c.kind().width()) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " data buffer is too short");
                }
                if (c.kind() == Kind.DECIMAL_18_6 || c.kind() == Kind.DECIMAL_9_4) {
                    checkDecimal(c, rows, data, frameIndex);
                }
            }
            views[i] = new ColumnView(nullCount > 0 ? validity : null, (int) nullCount, offsets, data);
        }
        if (canon.valueType() == DatapointValueType.MIXED) {
            ColumnView numeric = views[2];
            ColumnView text = views[3];
            for (int r = 0; r < rows; r++) {
                if (numeric.isSet(r) == text.isSet(r)) {
                    throw new FrameFormatException(Reason.VALUE_INVALID, frameIndex, "mixed row " + r + " must set exactly one of value_numeric and value_text");
                }
            }
        }
        return new Batch(rows, views);
    }

    private static ByteBuffer slice(ByteBuffer body, Buffer buffer, int frameIndex, String what) {
        long offset = buffer.offset();
        long length = buffer.length();
        if (offset < 0 || length < 0 || offset + length > body.limit()) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, what + " buffer lies outside the body");
        }
        if (length > 0 && (offset & 7) != 0) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, what + " buffer is not 8-byte aligned");
        }
        return body.slice((int) offset, (int) length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void checkUtf8(Column c, int rows, int nullCount, ByteBuffer validity, ByteBuffer offsets, ByteBuffer data, int frameIndex) {
        if ((long) offsets.remaining() < (long) (rows + 1) * 4) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " offsets buffer is too short");
        }
        int previous = offsets.getInt(0);
        if (previous < 0) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " has a negative first offset");
        }
        ColumnView view = new ColumnView(nullCount > 0 ? validity : null, nullCount, offsets, data);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        for (int r = 0; r < rows; r++) {
            int next = offsets.getInt((r + 1) * 4);
            if (next < previous) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " offsets decrease at row " + r);
            }
            if (next > data.remaining()) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " offsets run past the data at row " + r);
            }
            int length = next - previous;
            if (view.isSet(r)) {
                if (length > FrameLimits.MAX_VALUE_BYTES) {
                    throw new FrameFormatException(Reason.VALUE_INVALID, frameIndex, "row " + r + " value is " + length + " bytes, allowed " + FrameLimits.MAX_VALUE_BYTES);
                }
                CharBuffer chars;
                try {
                    chars = decoder.decode(data.slice(previous, length));
                } catch (CharacterCodingException e) {
                    throw new FrameFormatException(Reason.VALUE_INVALID, frameIndex, "row " + r + " value is not valid UTF-8");
                }
                if (chars.length() > FrameLimits.MAX_VALUE_CHARS) {
                    throw new FrameFormatException(Reason.VALUE_INVALID, frameIndex, "row " + r + " value is " + chars.length() + " characters, allowed " + FrameLimits.MAX_VALUE_CHARS);
                }
                decoder.reset();
            }
            previous = next;
        }
    }

    private static void checkDecimal(Column c, int rows, ByteBuffer data, int frameIndex) {
        long max = c.kind() == Kind.DECIMAL_18_6 ? FrameLimits.NUMERIC_UNSCALED_MAX : FrameLimits.DECIMAL32_UNSCALED_MAX;
        for (int r = 0; r < rows; r++) {
            long low = data.getLong(r * 16);
            long high = data.getLong(r * 16 + 8);
            if (high != (low >> 63) || low > max || low < -max) {
                throw new FrameFormatException(Reason.VALUE_INVALID, frameIndex, "row " + r + " decimal is outside Decimal(" + c.kind().precision + ", " + c.kind().scale + ")");
            }
        }
    }
}
