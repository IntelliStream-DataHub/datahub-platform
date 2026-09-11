// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnView;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One frame of a binary datapoint request, read in two steps: {@link #parseEnvelopes} checks the
 * envelopes and directories of a whole body without touching a payload, {@link #decode} then
 * decompresses one frame and validates its Arrow stream, row order and series. The API keeps the
 * frame's bytes as they arrived and forwards them; the consumer decodes them again.
 */
public final class DatapointFrame {

    /** A run of consecutive rows for one series, {@code from} inclusive to {@code to} exclusive. */
    public record Run(long id, String externalId, int from, int to) {
        public int rows() {
            return to - from;
        }
    }

    private final byte[] body;
    private final int frameOffset;
    private final int frameLength;
    private final int index;
    private final DatapointValueType valueType;
    private final int rowCount;
    private final long[] seriesIds;
    private final String[] externalIds;
    private final int payloadOffset;
    private final int payloadLength;
    private final int rawLength;

    private byte[] raw;
    private List<Batch> batches;
    private int[] batchStarts;
    private List<Run> runs;

    private DatapointFrame(byte[] body, int frameOffset, int frameLength, int index, DatapointValueType valueType,
                           int rowCount, long[] seriesIds, String[] externalIds,
                           int payloadOffset, int payloadLength, int rawLength) {
        this.body = body;
        this.frameOffset = frameOffset;
        this.frameLength = frameLength;
        this.index = index;
        this.valueType = valueType;
        this.rowCount = rowCount;
        this.seriesIds = seriesIds;
        this.externalIds = externalIds;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
        this.rawLength = rawLength;
    }

    /** Envelope and directory of every frame in {@code body}, with the request-level caps applied. */
    public static List<DatapointFrame> parseEnvelopes(byte[] body) {
        if (body == null || body.length == 0) {
            throw new FrameFormatException(Reason.MALFORMED_FRAME, 0, "empty body");
        }
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        List<DatapointFrame> frames = new ArrayList<>();
        long rawTotal = 0;
        int pos = 0;
        while (pos < body.length) {
            int i = frames.size();
            if (i == FrameLimits.MAX_FRAMES_PER_REQUEST) {
                throw new FrameFormatException(Reason.TOO_MANY_FRAMES, i, "more than " + FrameLimits.MAX_FRAMES_PER_REQUEST + " frames");
            }
            if (body.length - pos < FrameLimits.HEADER_BYTES) {
                throw new FrameFormatException(Reason.MALFORMED_FRAME, i, "only " + (body.length - pos) + " bytes left for a header");
            }
            for (int m = 0; m < 4; m++) {
                if (body[pos + m] != FrameLimits.MAGIC[m]) {
                    throw new FrameFormatException(Reason.MALFORMED_FRAME, i, "bad magic");
                }
            }
            int version = body[pos + 4] & 0xFF;
            if (version != FrameLimits.VERSION) {
                throw new FrameFormatException(Reason.UNSUPPORTED_VERSION, i, "version " + version);
            }
            int typeId = body[pos + 5] & 0xFF;
            DatapointValueType type;
            try {
                type = DatapointValueType.fromId(typeId);
            } catch (IllegalArgumentException e) {
                throw new FrameFormatException(Reason.UNKNOWN_VALUE_TYPE, i, "value type id " + typeId);
            }
            int codec = body[pos + 6] & 0xFF;
            if (codec != FrameLimits.CODEC_ARROW_IPC) {
                throw new FrameFormatException(Reason.UNSUPPORTED_CODEC, i, "codec " + codec);
            }
            int compression = body[pos + 7] & 0xFF;
            if (compression == 0) {
                throw new FrameFormatException(Reason.UNCOMPRESSED_FRAME, i, "frames must be zstd-compressed");
            }
            if (compression != FrameLimits.COMPRESSION_ZSTD) {
                throw new FrameFormatException(Reason.MALFORMED_FRAME, i, "compression " + compression);
            }
            long rows = buf.getInt(pos + 8) & 0xFFFFFFFFL;
            long seriesCount = buf.getInt(pos + 12) & 0xFFFFFFFFL;
            long directoryLength = buf.getInt(pos + 16) & 0xFFFFFFFFL;
            long payloadLength = buf.getInt(pos + 20) & 0xFFFFFFFFL;
            long rawLength = buf.getInt(pos + 24) & 0xFFFFFFFFL;
            int maxRows = FrameLimits.maxRows(type);
            if (rows < 1 || rows > maxRows) {
                throw new FrameFormatException(Reason.ROW_COUNT_MISMATCH, i, rows + " rows, allowed 1 to " + maxRows + " for " + type);
            }
            if (seriesCount < 1 || seriesCount > rows || seriesCount > FrameLimits.MAX_SERIES_PER_FRAME) {
                throw new FrameFormatException(Reason.DIRECTORY_INVALID, i, seriesCount + " series for " + rows + " rows");
            }
            if (rawLength < 1 || rawLength > FrameLimits.MAX_FRAME_RAW_BYTES) {
                throw new FrameFormatException(Reason.FRAME_TOO_LARGE, i, rawLength + " raw bytes, the cap is " + FrameLimits.MAX_FRAME_RAW_BYTES);
            }
            if (payloadLength < 1 || payloadLength > FrameLimits.MAX_FRAME_RAW_BYTES + 1024) {
                throw new FrameFormatException(Reason.MALFORMED_FRAME, i, payloadLength + " payload bytes");
            }
            long total = FrameLimits.HEADER_BYTES + directoryLength + payloadLength;
            if (total > body.length - pos) {
                throw new FrameFormatException(Reason.MALFORMED_FRAME, i, "frame declares " + total + " bytes, " + (body.length - pos) + " remain");
            }
            rawTotal += rawLength;
            if (rawTotal > FrameLimits.MAX_REQUEST_RAW_BYTES) {
                throw new FrameFormatException(Reason.REQUEST_TOO_LARGE, i, "request exceeds " + FrameLimits.MAX_REQUEST_RAW_BYTES + " raw bytes");
            }
            long[] ids = new long[(int) seriesCount];
            String[] names = new String[(int) seriesCount];
            readDirectory(buf, pos + FrameLimits.HEADER_BYTES, (int) directoryLength, ids, names, i);
            int payloadOffset = pos + FrameLimits.HEADER_BYTES + (int) directoryLength;
            frames.add(new DatapointFrame(body, pos, (int) total, i, type, (int) rows, ids, names,
                    payloadOffset, (int) payloadLength, (int) rawLength));
            pos += (int) total;
        }
        return frames;
    }

    private static void readDirectory(ByteBuffer buf, int start, int length, long[] ids, String[] names, int frameIndex) {
        ByteBuffer dir = buf.slice(start, length).order(ByteOrder.LITTLE_ENDIAN);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        long previous = Long.MIN_VALUE;
        for (int s = 0; s < ids.length; s++) {
            if (dir.remaining() < 9) {
                throw new FrameFormatException(Reason.DIRECTORY_INVALID, frameIndex, "directory ends inside entry " + s);
            }
            long id = dir.getLong();
            if (s > 0 && id <= previous) {
                throw new FrameFormatException(Reason.DIRECTORY_INVALID, frameIndex, "series ids are not strictly ascending at entry " + s);
            }
            previous = id;
            int len;
            try {
                len = Varint.read(dir, FrameLimits.MAX_EXTERNAL_ID_BYTES);
            } catch (IllegalArgumentException e) {
                throw new FrameFormatException(Reason.DIRECTORY_INVALID, frameIndex, "entry " + s + ": " + e.getMessage());
            }
            if (len == 0 || dir.remaining() < len) {
                throw new FrameFormatException(Reason.DIRECTORY_INVALID, frameIndex, "entry " + s + " external id length " + len);
            }
            try {
                names[s] = decoder.decode(dir.slice(dir.position(), len)).toString();
            } catch (CharacterCodingException e) {
                throw new FrameFormatException(Reason.DIRECTORY_INVALID, frameIndex, "entry " + s + " external id is not valid UTF-8");
            }
            decoder.reset();
            dir.position(dir.position() + len);
            ids[s] = id;
        }
        if (dir.hasRemaining()) {
            throw new FrameFormatException(Reason.DIRECTORY_INVALID, frameIndex, dir.remaining() + " bytes after the last entry");
        }
    }

    /** Decompresses and validates the payload; safe to call once per frame, from any thread. */
    public void decode(PayloadCodec codec) {
        byte[] compressed = payloadBytes();
        byte[] decoded;
        try {
            decoded = codec.decompress(compressed, rawLength);
        } catch (IllegalArgumentException e) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, index, e.getMessage());
        }
        if (decoded.length != rawLength) {
            throw new FrameFormatException(Reason.PAYLOAD_INVALID, index, "payload decompressed to " + decoded.length + " bytes, envelope says " + rawLength);
        }
        List<Batch> parsed = ArrowIpc.readStream(ArrowSchemaCanon.of(valueType), decoded, index, FrameLimits.maxRows(valueType));
        int[] starts = new int[parsed.size() + 1];
        for (int b = 0; b < parsed.size(); b++) {
            starts[b + 1] = starts[b] + parsed.get(b).rows();
        }
        if (starts[parsed.size()] != rowCount) {
            throw new FrameFormatException(Reason.ROW_COUNT_MISMATCH, index, "envelope says " + rowCount + " rows, batches hold " + starts[parsed.size()]);
        }
        List<Run> found = scanRuns(parsed);
        if (found.size() != seriesIds.length) {
            throw new FrameFormatException(Reason.DIRECTORY_INVALID, index, "directory lists " + seriesIds.length + " series, payload holds " + found.size());
        }
        for (int s = 0; s < found.size(); s++) {
            if (found.get(s).id() != seriesIds[s]) {
                throw new FrameFormatException(Reason.DIRECTORY_INVALID, index, "directory entry " + s + " is series " + seriesIds[s] + ", payload has " + found.get(s).id());
            }
        }
        this.raw = decoded;
        this.batches = Collections.unmodifiableList(parsed);
        this.batchStarts = starts;
        this.runs = Collections.unmodifiableList(found);
    }

    private List<Run> scanRuns(List<Batch> parsed) {
        List<Run> found = new ArrayList<>();
        long runId = 0;
        int runStart = 0;
        long previousId = 0;
        long previousTs = 0;
        int row = 0;
        boolean first = true;
        for (Batch batch : parsed) {
            ByteBuffer idData = batch.columns()[0].data();
            ByteBuffer tsData = batch.columns()[1].data();
            for (int r = 0; r < batch.rows(); r++, row++) {
                long id = idData.getLong(r * 8);
                long ts = tsData.getLong(r * 8);
                if (first) {
                    first = false;
                    runId = id;
                } else if (id < previousId) {
                    throw new FrameFormatException(Reason.UNSORTED, index, "row " + row + " has a smaller series id than row " + (row - 1));
                } else if (id == previousId) {
                    if (ts == previousTs) {
                        throw new FrameFormatException(Reason.DUPLICATE_ROW, index, "rows " + (row - 1) + " and " + row + " repeat (series " + id + ", timestamp " + ts + ")");
                    }
                    if (ts < previousTs) {
                        throw new FrameFormatException(Reason.UNSORTED, index, "row " + row + " goes back in time within series " + id);
                    }
                } else {
                    found.add(new Run(runId, externalIdFor(runId, found.size()), runStart, row));
                    runId = id;
                    runStart = row;
                }
                previousId = id;
                previousTs = ts;
            }
        }
        found.add(new Run(runId, externalIdFor(runId, found.size()), runStart, row));
        return found;
    }

    private String externalIdFor(long id, int runIndex) {
        return runIndex < seriesIds.length && seriesIds[runIndex] == id ? externalIds[runIndex] : null;
    }

    /** Envelopes, then each payload in turn; the convenience form for tests and small callers. */
    public static List<DatapointFrame> parseAll(byte[] body, PayloadCodec codec) {
        List<DatapointFrame> frames = parseEnvelopes(body);
        for (DatapointFrame f : frames) {
            f.decode(codec);
        }
        return frames;
    }

    public int index() {
        return index;
    }

    public DatapointValueType valueType() {
        return valueType;
    }

    public int rowCount() {
        return rowCount;
    }

    public int seriesCount() {
        return seriesIds.length;
    }

    public long[] seriesIds() {
        return seriesIds.clone();
    }

    public String[] externalIds() {
        return externalIds.clone();
    }

    public int payloadLength() {
        return payloadLength;
    }

    public int rawLength() {
        return rawLength;
    }

    /** The whole frame as it arrived, envelope, directory and compressed payload. */
    public byte[] frameBytes() {
        return Arrays.copyOfRange(body, frameOffset, frameOffset + frameLength);
    }

    public byte[] payloadBytes() {
        return Arrays.copyOfRange(body, payloadOffset, payloadOffset + payloadLength);
    }

    public boolean decoded() {
        return batches != null;
    }

    public List<Batch> batches() {
        requireDecoded();
        return batches;
    }

    public List<Run> runs() {
        requireDecoded();
        return runs;
    }

    public long id(int row) {
        return batchFor(row).columns()[0].data().getLong(rowInBatch(row) * 8);
    }

    public long timestamp(int row) {
        return batchFor(row).columns()[1].data().getLong(rowInBatch(row) * 8);
    }

    /** The value in the JSON contract's string form, for the fan-out and the latest-value cache. */
    public String valueAsString(int row) {
        Batch batch = batchFor(row);
        int r = rowInBatch(row);
        ColumnView value = batch.columns()[2];
        return switch (valueType) {
            case BIGINT -> Long.toString(value.data().getLong(r * 8));
            case FLOAT -> Double.toString(value.data().getDouble(r * 8));
            case FLOAT32 -> Float.toString(value.data().getFloat(r * 4));
            case NUMERIC -> BigDecimal.valueOf(value.data().getLong(r * 16), 6).toPlainString();
            case DECIMAL32 -> BigDecimal.valueOf(value.data().getLong(r * 16), 4).toPlainString();
            case TEXT -> value.utf8At(r);
            case MIXED -> value.isSet(r)
                    ? Double.toString(value.data().getDouble(r * 8))
                    : batch.columns()[3].utf8At(r);
        };
    }

    private void requireDecoded() {
        if (batches == null) {
            throw new IllegalStateException("frame " + index + " is not decoded");
        }
    }

    private Batch batchFor(int row) {
        requireDecoded();
        if (row < 0 || row >= rowCount) {
            throw new IndexOutOfBoundsException("row " + row + " of " + rowCount);
        }
        int b = Arrays.binarySearch(batchStarts, row);
        if (b < 0) {
            b = -b - 2;
        }
        while (b + 1 < batches.size() && batchStarts[b + 1] <= row) {
            b++;
        }
        return batches.get(b);
    }

    private int rowInBatch(int row) {
        int b = Arrays.binarySearch(batchStarts, row);
        if (b < 0) {
            b = -b - 2;
        }
        while (b + 1 < batches.size() && batchStarts[b + 1] <= row) {
            b++;
        }
        return row - batchStarts[b];
    }
}
