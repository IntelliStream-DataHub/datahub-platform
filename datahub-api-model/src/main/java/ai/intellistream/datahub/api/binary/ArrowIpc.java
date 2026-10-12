// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowSchemaCanon.Column;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon.Kind;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;
import com.google.flatbuffers.FlatBufferBuilder;
import org.apache.arrow.flatbuf.BodyCompression;
import org.apache.arrow.flatbuf.BodyCompressionMethod;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.CompressionType;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.MetadataVersion;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes Arrow IPC streams, limited to what the datapoint contract allows. This is where
 * the whole package's Arrow handling lives, and why it is written by hand.
 *
 * <p>Arrow's own Java library, {@code arrow-vector}, would do this for us. We do not use it. On
 * Java 25 it does not start at all without four JVM flags, and this module ships to SDK users, so
 * every one of them would have to add those flags to run. It also pulls in 14 jars instead of 2,
 * and it holds request data in off-heap memory, which {@code -Xmx} does not limit.
 *
 * <p>What we use instead is {@code arrow-format}, Arrow's generated FlatBuffers classes for the
 * IPC metadata. The message framing and buffer layout around them are written here, on the heap.
 *
 * <p>Little is lost. Arrow's Java verifier is off by default, so the library would not have checked
 * these bytes either, and {@link #readStream} rejects bad input explicitly. The Rust SDK does use
 * the full arrow-rs crates, which have neither problem, and frames written by one parse in the
 * other.
 *
 * <p>Record batches may compress their buffers in-band, Arrow's body compression with zstd by
 * buffer ({@link FrameLimits#COMPRESSION_ARROW_ZSTD}): each buffer is then its uncompressed length
 * as a little-endian {@code int64}, then the zstd stream, or {@code -1} and the bytes as they are;
 * an empty buffer is just empty. Which streams must, may or may not carry it is the caller's
 * choice, and the codec that undoes it is supplied, so this module still carries no native code.
 */
public final class ArrowIpc {

    private ArrowIpc() {
    }

    private static final int CONTINUATION = 0xFFFFFFFF;
    private static final byte[] END_OF_STREAM = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};
    /** The length prefix of an in-band buffer stored as it is, uncompressed. */
    private static final long STORED_RAW = -1L;

    /** Whether the record batches of a stream compress their buffers in-band. */
    private enum InBand {
        /** Not allowed: the stream of a {@link FrameLimits#COMPRESSION_ZSTD} frame, decompressed. */
        REFUSED,
        /** Every record batch must: the payload of a {@link FrameLimits#COMPRESSION_ARROW_ZSTD} frame. */
        REQUIRED,
        /** As it comes: ClickHouse's answer to a read, asked to compress but not relied on. */
        ALLOWED
    }

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

        /** The same buffers, to write into another stream. */
        public BatchData toData() {
            ColumnData[] data = new ColumnData[columns.length];
            for (int i = 0; i < columns.length; i++) {
                ColumnView c = columns[i];
                data[i] = new ColumnData(c.validity(), c.nullCount(), c.offsets(), c.data());
            }
            return new BatchData(rows, data);
        }

        /**
         * Rows {@code from} inclusive to {@code to} exclusive, to write into another stream: how a
         * record batch longer than a frame is cut. Fixed-width and text bytes are views into this
         * batch; text offsets are rebased and a validity bitmap re-packed, since neither can be
         * sliced in place.
         */
        public BatchData slice(ArrowSchemaCanon canon, int from, int to) {
            if (from == 0 && to == rows) {
                return toData();
            }
            if (from < 0 || to > rows || from >= to) {
                throw new IllegalArgumentException("rows " + from + " to " + to + " of " + rows);
            }
            int n = to - from;
            ColumnData[] data = new ColumnData[columns.length];
            for (int i = 0; i < columns.length; i++) {
                ColumnView c = columns[i];
                ByteBuffer validity = null;
                int nullCount = 0;
                if (c.nullCount() > 0) {
                    validity = ByteBuffer.allocate((n + 7) >>> 3);
                    for (int r = 0; r < n; r++) {
                        if (c.isSet(from + r)) {
                            validity.put(r >>> 3, (byte) (validity.get(r >>> 3) | (1 << (r & 7))));
                        } else {
                            nullCount++;
                        }
                    }
                }
                Kind kind = canon.columns().get(i).kind();
                if (kind == Kind.UTF8) {
                    int base = c.offsetAt(from);
                    ByteBuffer offsets = ByteBuffer.allocate((n + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (int r = 0; r <= n; r++) {
                        offsets.putInt(r * 4, c.offsetAt(from + r) - base);
                    }
                    ByteBuffer text = c.data().slice(base, c.offsetAt(to) - base).order(ByteOrder.LITTLE_ENDIAN);
                    data[i] = new ColumnData(validity, nullCount, offsets, text);
                } else {
                    int width = kind.width();
                    data[i] = new ColumnData(validity, nullCount, null, c.data().slice(from * width, n * width).order(ByteOrder.LITTLE_ENDIAN));
                }
            }
            return new BatchData(n, data);
        }
    }

    /** The bytes of one column to write: the same shape as a view, from any source. */
    public record ColumnData(ByteBuffer validity, int nullCount, ByteBuffer offsets, ByteBuffer data) {
    }

    public record BatchData(int rows, ColumnData[] columns) {
    }

    /**
     * One record batch message as it was read, to relay into a frame whole: {@code metadata} and
     * {@code body} as sent, the batch's {@code rows}, whether its buffers are {@code compressed}
     * in-band, and {@code rawBodyBytes}, the body's length with every buffer uncompressed, which
     * is what the batch adds to a {@link FrameLimits#COMPRESSION_ARROW_ZSTD} frame's raw length.
     */
    public record RawBatch(byte[] metadata, byte[] body, int rows, boolean compressed, long rawBodyBytes) {

        /** The message as sent: continuation marker, metadata length, metadata, body. */
        public void writeTo(OutputStream out) throws IOException {
            ByteBuffer prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            prefix.putInt(CONTINUATION);
            prefix.putInt(metadata.length);
            out.write(prefix.array());
            out.write(metadata);
            out.write(body);
        }

        /** The message's length as sent. */
        public long length() {
            return 8L + metadata.length + body.length;
        }
    }

    static int pad8(int n) {
        return (n + 7) & ~7;
    }

    static long pad8(long n) {
        return (n + 7) & ~7L;
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
        return writeStream(canon, batches, null);
    }

    /**
     * As {@link #writeStream(ArrowSchemaCanon, List)}, every record batch compressing its buffers
     * in-band with {@code inBand} when one is given: the payload of a
     * {@link FrameLimits#COMPRESSION_ARROW_ZSTD} frame.
     */
    public static byte[] writeStream(ArrowSchemaCanon canon, List<BatchData> batches, PayloadCodec inBand) {
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
            writeBatch(canon, batch, out, inBand);
        }
        out.write(END_OF_STREAM, 0, END_OF_STREAM.length);
        return out.toByteArray();
    }

    /**
     * Writes one record batch message, its buffers compressed in-band when {@code inBand} is given.
     * Returns the body's length with every buffer uncompressed: what the batch adds to a
     * {@link FrameLimits#COMPRESSION_ARROW_ZSTD} frame's raw length.
     */
    static long writeBatch(ArrowSchemaCanon canon, BatchData batch, ByteArrayOutputStream out, PayloadCodec inBand) {
        List<Column> columns = canon.columns();
        if (batch.columns().length != columns.size()) {
            throw new IllegalArgumentException("batch has " + batch.columns().length + " columns, canon has " + columns.size());
        }
        // The buffers in Arrow's order: validity, offsets for Utf8, data. Arrow lets a column with
        // no nulls leave its validity bitmap out.
        int bufferCount = canon.bufferCount();
        ByteBuffer[] slices = new ByteBuffer[bufferCount];
        int b = 0;
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            ColumnData d = batch.columns()[i];
            slices[b++] = c.nullable() && d.nullCount() > 0 ? requireNonNull(d.validity(), c.name() + " validity") : null;
            if (c.kind() == Kind.UTF8) {
                slices[b++] = requireNonNull(d.offsets(), c.name() + " offsets");
            }
            slices[b++] = requireNonNull(d.data(), c.name() + " data");
        }
        // Lay the body out first so the record batch metadata can point into it.
        byte[][] stored = new byte[bufferCount][];
        long[] offsets = new long[bufferCount];
        long[] lengths = new long[bufferCount];
        int position = 0;
        long rawBody = 0;
        for (int i = 0; i < bufferCount; i++) {
            offsets[i] = position;
            int n = slices[i] == null ? 0 : slices[i].remaining();
            if (n == 0) {
                continue;
            }
            byte[] bytes = new byte[n];
            slices[i].duplicate().get(bytes);
            stored[i] = inBand == null ? bytes : compressBuffer(bytes, inBand);
            lengths[i] = stored[i].length;
            position += pad8(stored[i].length);
            rawBody += pad8(n);
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
        int compression = inBand == null ? 0 : BodyCompression.createBodyCompression(fb, CompressionType.ZSTD, BodyCompressionMethod.BUFFER);
        int recordBatch = RecordBatch.createRecordBatch(fb, batch.rows(), nodes, buffers, compression, 0);
        int message = Message.createMessage(fb, MetadataVersion.V5, MessageHeader.RecordBatch, recordBatch, bodyLength, 0);
        fb.finish(message);
        byte[] header = encapsulate(fb.sizedByteArray());
        out.write(header, 0, header.length);

        byte[] pad = new byte[8];
        for (int i = 0; i < bufferCount; i++) {
            if (stored[i] == null) {
                continue;
            }
            out.write(stored[i], 0, stored[i].length);
            out.write(pad, 0, pad8(stored[i].length) - stored[i].length);
        }
        return rawBody;
    }

    /** A buffer in its in-band form: its length and the zstd stream, or -1 and the bytes themselves when that is no shorter. */
    private static byte[] compressBuffer(byte[] bytes, PayloadCodec codec) {
        byte[] compressed = codec.compress(bytes);
        boolean raw = compressed.length >= bytes.length;
        byte[] payload = raw ? bytes : compressed;
        ByteBuffer out = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putLong(raw ? STORED_RAW : bytes.length);
        out.put(payload);
        return out.array();
    }

    private static ByteBuffer requireNonNull(ByteBuffer buffer, String what) {
        if (buffer == null) {
            throw new IllegalArgumentException(what + " buffer is missing");
        }
        return buffer;
    }

    /**
     * Parses one stream against the canon and returns its batches as views. Every rejection in the
     * contract's list lands here, as a {@link FrameFormatException} naming the frame. Record
     * batches with in-band compressed buffers are refused: the stream is a
     * {@link FrameLimits#COMPRESSION_ZSTD} payload, decompressed.
     */
    public static List<Batch> readStream(ArrowSchemaCanon canon, byte[] payload, int frameIndex, int maxRows) {
        return readStream(canon, payload, frameIndex, maxRows, false);
    }

    /**
     * As {@link #readStream(ArrowSchemaCanon, byte[], int, int)}, but a stream of only a schema is
     * an empty list rather than a rejection when {@code allowEmpty}: how ClickHouse answers a query
     * that matched no rows.
     */
    public static List<Batch> readStream(ArrowSchemaCanon canon, byte[] payload, int frameIndex, int maxRows, boolean allowEmpty) {
        return readStream(canon, payload, frameIndex, maxRows, allowEmpty, InBand.REFUSED, null, 0);
    }

    /**
     * As {@link #readStream(ArrowSchemaCanon, byte[], int, int)} for the payload of a
     * {@link FrameLimits#COMPRESSION_ARROW_ZSTD} frame: every record batch must compress its
     * buffers in-band, {@code codec} undoes that, and the bodies' length with every buffer
     * uncompressed must be {@code rawLength}, the envelope's.
     */
    public static List<Batch> readCompressedStream(ArrowSchemaCanon canon, byte[] payload, int frameIndex, int maxRows,
                                                   PayloadCodec codec, int rawLength) {
        return readStream(canon, payload, frameIndex, maxRows, false, InBand.REQUIRED, codec, rawLength);
    }

    private static List<Batch> readStream(ArrowSchemaCanon canon, byte[] payload, int frameIndex, int maxRows, boolean allowEmpty,
                                          InBand inBand, PayloadCodec codec, long rawLength) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        List<Batch> batches = new ArrayList<>();
        boolean schemaSeen = false;
        boolean ended = false;
        long rawTotal = 0;
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
            Message message = readMessage(buf.slice(pos + 8, metaLen).order(ByteOrder.LITTLE_ENDIAN), frameIndex, "message at " + pos);
            long bodyLength = message.bodyLength();
            int bodyStart = pos + 8 + metaLen;
            if (bodyLength < 0 || bodyStart + bodyLength > payload.length) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message at " + pos + " declares " + bodyLength + " body bytes");
            }
            byte headerType = message.headerType();
            if (headerType == MessageHeader.Schema) {
                if (schemaSeen) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "more than one schema message");
                }
                checkSchema(canon, message, bodyLength, frameIndex);
                schemaSeen = true;
            } else if (headerType == MessageHeader.RecordBatch) {
                if (!schemaSeen) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch before the schema");
                }
                ByteBuffer body = buf.slice(bodyStart, (int) bodyLength).order(ByteOrder.LITTLE_ENDIAN);
                RecordBatch rb = recordBatch(canon, message, frameIndex, maxRows, inBand);
                Inflated inflated = inflate(rb, body, codec, inBand == InBand.REQUIRED ? rawLength : bodyLength, frameIndex);
                rawTotal += inflated.rawBytes();
                batches.add(columns(canon, rb, inflated, frameIndex));
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
        if (batches.isEmpty() && !allowEmpty) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "no record batch");
        }
        if (inBand == InBand.REQUIRED && rawTotal != rawLength) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "buffers decompress to " + rawTotal + " bytes, envelope says " + rawLength);
        }
        return batches;
    }

    private static Message readMessage(ByteBuffer metadata, int frameIndex, String which) {
        Message message;
        try {
            message = Message.getRootAsMessage(metadata);
            message.bodyLength();
        } catch (RuntimeException e) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, which + " is not a readable FlatBuffer");
        }
        if (message.version() < MetadataVersion.V4) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "metadata version " + message.version() + " is older than V4");
        }
        return message;
    }

    private static void checkSchema(ArrowSchemaCanon canon, Message message, long bodyLength, int frameIndex) {
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
    }

    /**
     * Reads a stream of any length from an {@link InputStream} one message at a time, holding one
     * record batch at a time: how the API frames ClickHouse's answer to a binary read as it arrives,
     * however many points the window holds. {@link #next()} returns the batches in order, checked
     * like {@link #readStream}, and {@code null} once the end-of-stream marker has been read and
     * nothing follows it; a stream of only a schema yields no batch. With no total length to check
     * a message against, its declared sizes are held to what the caller will have: metadata over
     * {@value #MAX_METADATA_BYTES} bytes or a body over {@code maxBodyBytes}, compressed or not, is
     * rejected before a byte of it is read, and a batch over {@code maxRows} as in
     * {@link #readStream}. {@link #nextRaw()} is the message itself, checked only as far as is
     * needed to relay it into a frame whole.
     */
    public static final class StreamReader {

        /** A schema or record-batch message of the canon is well under a kilobyte. */
        public static final int MAX_METADATA_BYTES = 1024 * 1024;

        private static final byte[] NO_BODY = new byte[0];

        private final ArrowSchemaCanon canon;
        private final InputStream in;
        private final int frameIndex;
        private final int maxRows;
        private final long maxBodyBytes;
        private final PayloadCodec inBand;
        private boolean schemaSeen;
        private boolean ended;
        private int messages;

        /** A reader of a stream whose record batches do not compress their buffers in-band. */
        public StreamReader(ArrowSchemaCanon canon, InputStream in, int frameIndex, int maxRows, long maxBodyBytes) {
            this(canon, in, frameIndex, maxRows, maxBodyBytes, null);
        }

        /**
         * A reader of a stream whose record batches may compress their buffers in-band, as
         * ClickHouse's answer to a read does when asked: {@code inBand} undoes that in
         * {@link #decode}.
         */
        public StreamReader(ArrowSchemaCanon canon, InputStream in, int frameIndex, int maxRows, long maxBodyBytes, PayloadCodec inBand) {
            this.canon = canon;
            this.in = in;
            this.frameIndex = frameIndex;
            this.maxRows = maxRows;
            this.maxBodyBytes = maxBodyBytes;
            this.inBand = inBand;
        }

        /** The next record batch, or {@code null} at the end of the stream. */
        public Batch next() throws IOException {
            RawBatch raw = nextRaw();
            return raw == null ? null : decode(raw);
        }

        /**
         * The next record batch message as it was read, or {@code null} at the end of the stream.
         * Its header, row count and buffer layout are checked; its values are not, which is
         * {@link #decode}'s job and is not needed to relay it.
         */
        public RawBatch nextRaw() throws IOException {
            while (!ended) {
                byte[] prefix = in.readNBytes(8);
                if (prefix.length < 8) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "stream ends without an end-of-stream marker");
                }
                ByteBuffer p = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);
                if (p.getInt(0) != CONTINUATION) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message " + messages + " lacks the continuation marker");
                }
                int metaLen = p.getInt(4);
                if (metaLen == 0) {
                    ended = true;
                    if (in.read() != -1) {
                        throw new FrameFormatException(Reason.TRAILING_BYTES, frameIndex, "bytes after the end-of-stream marker");
                    }
                    return null;
                }
                if (metaLen < 0 || metaLen > MAX_METADATA_BYTES) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message " + messages + " declares " + metaLen + " metadata bytes");
                }
                byte[] metadata = read(metaLen);
                Message message = readMessage(ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN), frameIndex, "message " + messages);
                long bodyLength = message.bodyLength();
                if (bodyLength < 0 || bodyLength > maxBodyBytes) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "message " + messages + " declares " + bodyLength + " body bytes");
                }
                byte[] body = bodyLength == 0 ? NO_BODY : read((int) bodyLength);
                messages++;
                byte headerType = message.headerType();
                if (headerType == MessageHeader.Schema) {
                    if (schemaSeen) {
                        throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "more than one schema message");
                    }
                    checkSchema(canon, message, bodyLength, frameIndex);
                    schemaSeen = true;
                } else if (headerType == MessageHeader.RecordBatch) {
                    if (!schemaSeen) {
                        throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch before the schema");
                    }
                    RecordBatch rb = recordBatch(canon, message, frameIndex, maxRows, rule());
                    ByteBuffer bodyBuf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                    boolean compressed = rb.compression() != null;
                    long rawBody = compressed ? inflatedBodyBytes(rb, bodyBuf, maxBodyBytes, frameIndex) : layout(rb, bodyBuf, frameIndex).rawBytes();
                    return new RawBatch(metadata, body, (int) rb.length(), compressed, rawBody);
                } else if (headerType == MessageHeader.DictionaryBatch) {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "dictionary batches are not allowed");
                } else {
                    throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "unexpected message type " + headerType);
                }
            }
            return null;
        }

        /**
         * The batch of a message {@link #nextRaw()} returned, with every check {@link #readStream}
         * makes, its buffers decompressed when they were compressed.
         */
        public Batch decode(RawBatch raw) {
            Message message = readMessage(ByteBuffer.wrap(raw.metadata()).order(ByteOrder.LITTLE_ENDIAN), frameIndex, "record batch");
            RecordBatch rb = recordBatch(canon, message, frameIndex, maxRows, rule());
            Inflated inflated = inflate(rb, ByteBuffer.wrap(raw.body()).order(ByteOrder.LITTLE_ENDIAN), inBand, maxBodyBytes, frameIndex);
            return columns(canon, rb, inflated, frameIndex);
        }

        private InBand rule() {
            return inBand == null ? InBand.REFUSED : InBand.ALLOWED;
        }

        private byte[] read(int length) throws IOException {
            byte[] bytes = in.readNBytes(length);
            if (bytes.length < length) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "stream ends inside message " + messages);
            }
            return bytes;
        }
    }

    /** The record batch header of a message, checked against the canon and the in-band rule. */
    private static RecordBatch recordBatch(ArrowSchemaCanon canon, Message message, int frameIndex, int maxRows, InBand inBand) {
        RecordBatch rb;
        try {
            rb = (RecordBatch) message.header(new RecordBatch());
        } catch (RuntimeException e) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch is not a readable FlatBuffer");
        }
        if (rb == null) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch message has no record batch");
        }
        BodyCompression compression = rb.compression();
        if (compression != null) {
            if (inBand == InBand.REFUSED) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch bodies must not be compressed");
            }
            if (compression.codec() != CompressionType.ZSTD) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch buffers are compressed with codec " + compression.codec() + ", not zstd");
            }
            if (compression.method() != BodyCompressionMethod.BUFFER) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch buffers are compressed by method " + compression.method() + ", not by buffer");
            }
        } else if (inBand == InBand.REQUIRED) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch bodies must be compressed in-band");
        }
        if (rb.variadicBufferCountsLength() != 0) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "variadic buffers are not allowed");
        }
        long rowsLong = rb.length();
        if (rowsLong < 1 || rowsLong > maxRows) {
            throw new FrameFormatException(Reason.ROW_COUNT_MISMATCH, frameIndex, "record batch has " + rowsLong + " rows, allowed 1 to " + maxRows);
        }
        List<Column> columns = canon.columns();
        if (rb.nodesLength() != columns.size()) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch has " + rb.nodesLength() + " field nodes, expected " + columns.size());
        }
        if (rb.buffersLength() != canon.bufferCount()) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "record batch has " + rb.buffersLength() + " buffers, expected " + canon.bufferCount());
        }
        return rb;
    }

    /**
     * A record batch's buffers, uncompressed: the body as sent and where each buffer lies in it,
     * or a body inflated from the in-band form. {@code rawBytes} is that body's length.
     */
    private record Inflated(ByteBuffer body, long[] offsets, long[] lengths, long rawBytes) {
    }

    /** The layout of a body whose buffers are not compressed, each checked to lie inside it, aligned. */
    private static Inflated layout(RecordBatch rb, ByteBuffer body, int frameIndex) {
        int n = rb.buffersLength();
        long[] offsets = new long[n];
        long[] lengths = new long[n];
        long rawBytes = 0;
        for (int i = 0; i < n; i++) {
            Buffer buffer = rb.buffers(i);
            checkInside(body, buffer, frameIndex, i);
            offsets[i] = buffer.offset();
            lengths[i] = buffer.length();
            rawBytes += pad8(buffer.length());
        }
        return new Inflated(body, offsets, lengths, rawBytes);
    }

    private static void checkInside(ByteBuffer body, Buffer buffer, int frameIndex, int index) {
        long offset = buffer.offset();
        long length = buffer.length();
        if (offset < 0 || length < 0 || offset + length > body.limit()) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "buffer " + index + " lies outside the body");
        }
        if (length > 0 && (offset & 7) != 0) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "buffer " + index + " is not 8-byte aligned");
        }
    }

    /**
     * The uncompressed length each in-band buffer declares, checked to lie inside the body and to
     * add up to at most {@code maxRawBytes}: an empty buffer is empty, any other is at least its
     * 8-byte prefix.
     */
    private static long[] declaredLengths(RecordBatch rb, ByteBuffer body, long maxRawBytes, int frameIndex) {
        int n = rb.buffersLength();
        long[] raw = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            Buffer buffer = rb.buffers(i);
            checkInside(body, buffer, frameIndex, i);
            long length = buffer.length();
            if (length == 0) {
                continue;
            }
            if (length < 8) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "buffer " + i + " is " + length + " bytes, too short for its length prefix");
            }
            long declared = body.getLong((int) buffer.offset());
            raw[i] = declared == STORED_RAW ? length - 8 : declared;
            if (raw[i] < 0 || raw[i] > maxRawBytes - total) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "buffer " + i + " declares " + declared + " uncompressed bytes");
            }
            total += pad8(raw[i]);
        }
        return raw;
    }

    /** The body's length with every in-band buffer uncompressed, from the prefixes alone. */
    private static long inflatedBodyBytes(RecordBatch rb, ByteBuffer body, long maxRawBytes, int frameIndex) {
        long total = 0;
        for (long raw : declaredLengths(rb, body, maxRawBytes, frameIndex)) {
            total += pad8(raw);
        }
        return total;
    }

    /** The body with its in-band buffers decompressed by {@code codec}, or as it is when they are not compressed. */
    private static Inflated inflate(RecordBatch rb, ByteBuffer body, PayloadCodec codec, long maxRawBytes, int frameIndex) {
        if (rb.compression() == null) {
            return layout(rb, body, frameIndex);
        }
        long[] raw = declaredLengths(rb, body, maxRawBytes, frameIndex);
        int n = raw.length;
        long[] offsets = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            offsets[i] = total;
            total += pad8(raw[i]);
        }
        byte[] out = new byte[(int) total];
        for (int i = 0; i < n; i++) {
            if (raw[i] == 0) {
                continue;
            }
            Buffer buffer = rb.buffers(i);
            int offset = (int) buffer.offset();
            int length = (int) buffer.length();
            if (body.getLong(offset) == STORED_RAW) {
                body.get(offset + 8, out, (int) offsets[i], length - 8);
                continue;
            }
            byte[] bytes;
            try {
                bytes = codec.decompress(body.array(), body.arrayOffset() + offset + 8, length - 8, (int) raw[i]);
            } catch (IllegalArgumentException e) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "buffer " + i + ": " + e.getMessage());
            }
            System.arraycopy(bytes, 0, out, (int) offsets[i], bytes.length);
        }
        return new Inflated(ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN), offsets, raw, total);
    }

    /** The batch's columns as views into the uncompressed body, every value checked. */
    private static Batch columns(ArrowSchemaCanon canon, RecordBatch rb, Inflated inflated, int frameIndex) {
        int rows = (int) rb.length();
        List<Column> columns = canon.columns();
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
            ByteBuffer validity = slice(inflated, b++);
            if (nullCount > 0 && validity.remaining() < bitmapBytes) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frameIndex, "field " + c.name() + " validity bitmap is too short");
            }
            ByteBuffer offsets = null;
            ByteBuffer data;
            if (c.kind() == Kind.UTF8) {
                offsets = slice(inflated, b++);
                data = slice(inflated, b++);
                checkUtf8(c, rows, (int) nullCount, validity, offsets, data, frameIndex);
            } else {
                data = slice(inflated, b++);
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

    private static ByteBuffer slice(Inflated inflated, int index) {
        return inflated.body().slice((int) inflated.offsets()[index], (int) inflated.lengths()[index]).order(ByteOrder.LITTLE_ENDIAN);
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
