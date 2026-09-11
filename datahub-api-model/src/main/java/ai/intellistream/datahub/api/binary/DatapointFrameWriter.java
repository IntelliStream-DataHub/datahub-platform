// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.BatchData;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one datapoint frame for one value type: rows in any order in, a sorted, de-duplicated,
 * compressed frame out. The per-type value rules (Decimal scaling and rounding, the Decimal32
 * clamp, the mixed numeric-or-text probe) are the ones the JSON path applies on the server, so
 * the two paths store the same bytes for the same input.
 *
 * @see ArrowIpc why this package handles Arrow itself
 */
public final class DatapointFrameWriter {

    private static final BigDecimal DECIMAL32_MAX = new BigDecimal("99999.9999");
    private static final BigDecimal DECIMAL32_MIN = DECIMAL32_MAX.negate();

    private final DatapointValueType type;
    private final ArrowSchemaCanon canon;
    private final Map<Long, String> series = new HashMap<>();

    private long[] ids = new long[1024];
    private long[] timestamps = new long[1024];
    private long[] longs;
    private double[] doubles;
    private float[] floats;
    private byte[][] texts;
    private int size;
    private long textBytes;
    private int clamped;

    private DatapointFrameWriter(DatapointValueType type) {
        this.type = type;
        this.canon = ArrowSchemaCanon.of(type);
        switch (type) {
            case BIGINT, NUMERIC, DECIMAL32 -> longs = new long[1024];
            case FLOAT -> doubles = new double[1024];
            case FLOAT32 -> floats = new float[1024];
            case TEXT -> texts = new byte[1024][];
            case MIXED -> {
                doubles = new double[1024];
                texts = new byte[1024][];
            }
        }
    }

    public static DatapointFrameWriter forType(DatapointValueType type) {
        return new DatapointFrameWriter(type);
    }

    public DatapointValueType type() {
        return type;
    }

    /** Names a series the frame will carry; every id added must have one before {@link #build}. */
    public DatapointFrameWriter series(long id, String externalId) {
        if (externalId == null || externalId.isEmpty()) {
            throw new IllegalArgumentException("external id for series " + id + " is empty");
        }
        if (externalId.getBytes(StandardCharsets.UTF_8).length > FrameLimits.MAX_EXTERNAL_ID_BYTES) {
            throw new IllegalArgumentException("external id for series " + id + " exceeds " + FrameLimits.MAX_EXTERNAL_ID_BYTES + " bytes");
        }
        series.put(id, externalId);
        return this;
    }

    public void addBigint(long id, long timestamp, long value) {
        expect(DatapointValueType.BIGINT);
        int i = row(id, timestamp);
        longs[i] = value;
    }

    public void addFloat(long id, long timestamp, double value) {
        expect(DatapointValueType.FLOAT);
        int i = row(id, timestamp);
        doubles[i] = value;
    }

    public void addFloat32(long id, long timestamp, float value) {
        expect(DatapointValueType.FLOAT32);
        int i = row(id, timestamp);
        floats[i] = value;
    }

    /** Rounded half-up to six decimals; a value outside Decimal(18, 6) is refused. */
    public void addNumeric(long id, long timestamp, BigDecimal value) {
        expect(DatapointValueType.NUMERIC);
        long unscaled = value.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (unscaled > FrameLimits.NUMERIC_UNSCALED_MAX || unscaled < -FrameLimits.NUMERIC_UNSCALED_MAX) {
            throw new IllegalArgumentException("numeric value " + value.toPlainString() + " does not fit Decimal(18, 6)");
        }
        int i = row(id, timestamp);
        longs[i] = unscaled;
    }

    /** Rounded half-up to four decimals and clamped to Decimal32(4)'s range, as the JSON path does. */
    public void addDecimal32(long id, long timestamp, BigDecimal value) {
        expect(DatapointValueType.DECIMAL32);
        BigDecimal bounded = value;
        if (value.compareTo(DECIMAL32_MAX) > 0 || value.compareTo(DECIMAL32_MIN) < 0) {
            bounded = value.signum() > 0 ? DECIMAL32_MAX : DECIMAL32_MIN;
            clamped++;
        }
        int i = row(id, timestamp);
        longs[i] = bounded.movePointRight(4).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    public void addText(long id, long timestamp, String value) {
        expect(DatapointValueType.TEXT);
        byte[] bytes = textBytes(value);
        int i = row(id, timestamp);
        texts[i] = bytes;
        textBytes += bytes.length;
    }

    /** Numeric when the value parses as a decimal number, text otherwise, as the JSON path decides. */
    public void addMixed(long id, long timestamp, String value) {
        expect(DatapointValueType.MIXED);
        Double numeric = null;
        try {
            new BigDecimal(value);
            numeric = Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException ignored) {
            // text
        }
        if (numeric != null) {
            int i = row(id, timestamp);
            doubles[i] = numeric;
            texts[i] = null;
        } else {
            byte[] bytes = textBytes(value);
            int i = row(id, timestamp);
            texts[i] = bytes;
            textBytes += bytes.length;
        }
    }

    /** Adds a value in the JSON contract's string form, parsed by the frame's value type. */
    public void add(long id, long timestamp, String value) {
        switch (type) {
            case BIGINT -> addBigint(id, timestamp, Long.parseLong(value));
            case FLOAT -> addFloat(id, timestamp, Double.parseDouble(value));
            case FLOAT32 -> addFloat32(id, timestamp, Float.parseFloat(value));
            case NUMERIC -> addNumeric(id, timestamp, new BigDecimal(value));
            case DECIMAL32 -> addDecimal32(id, timestamp, new BigDecimal(value));
            case TEXT -> addText(id, timestamp, value);
            case MIXED -> addMixed(id, timestamp, value);
        }
    }

    public int rowCount() {
        return size;
    }

    public int seriesCount() {
        return series.size();
    }

    /** Decimal32 values the writer had to clamp, for the caller to warn about. */
    public int clampedCount() {
        return clamped;
    }

    /** Uncompressed payload bytes this frame would need, for chunking before {@link #build}. */
    public long estimatedRawBytes() {
        long perRow = switch (type) {
            case BIGINT, FLOAT -> 24;
            case FLOAT32 -> 20;
            case NUMERIC, DECIMAL32 -> 32;
            case TEXT -> 20;
            case MIXED -> 28;
        };
        long bitmaps = type == DatapointValueType.MIXED ? 2L * ((size + 7) / 8) : 0;
        // Schema message, record batch message, end-of-stream, one row of padding per buffer, the
        // extra Utf8 offset, then the rows.
        long fixed = canon.schemaMessageLength() + 256 + 8 + 8L * canon.bufferCount() + 8;
        return fixed + perRow * size + textBytes + bitmaps;
    }

    /**
     * Sorts by (id, timestamp), keeps the last value for a repeated pair, checks the caps, writes the
     * Arrow stream, compresses it and returns the complete frame.
     */
    public byte[] build(PayloadCodec codec) {
        if (size == 0) {
            throw new IllegalStateException("frame has no rows");
        }
        int[] order = sortedOrder();
        int n = dedupeKeepingLast(order);
        int maxRows = FrameLimits.maxRows(type);
        if (n > maxRows) {
            throw new IllegalStateException("frame has " + n + " rows, the cap for " + type + " is " + maxRows + "; split it");
        }
        long[] directoryIds = distinctIds(order, n);
        if (directoryIds.length > FrameLimits.MAX_SERIES_PER_FRAME) {
            throw new IllegalStateException("frame has " + directoryIds.length + " series, the cap is " + FrameLimits.MAX_SERIES_PER_FRAME + "; split it");
        }
        byte[][] externalIds = new byte[directoryIds.length][];
        int directoryLength = 0;
        for (int i = 0; i < directoryIds.length; i++) {
            String externalId = series.get(directoryIds[i]);
            if (externalId == null) {
                throw new IllegalStateException("no external id registered for series " + directoryIds[i]);
            }
            externalIds[i] = externalId.getBytes(StandardCharsets.UTF_8);
            directoryLength += 8 + Varint.size(externalIds[i].length) + externalIds[i].length;
        }

        byte[] raw = ArrowIpc.writeStream(canon, List.of(new BatchData(n, columns(order, n))));
        if (raw.length > FrameLimits.MAX_FRAME_RAW_BYTES) {
            throw new IllegalStateException("frame payload is " + raw.length + " bytes, the cap is " + FrameLimits.MAX_FRAME_RAW_BYTES + "; split it");
        }
        byte[] compressed = codec.compress(raw);

        ByteBuffer frame = ByteBuffer.allocate(FrameLimits.HEADER_BYTES + directoryLength + compressed.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        frame.put(FrameLimits.MAGIC);
        frame.put((byte) FrameLimits.VERSION);
        frame.put((byte) type.id());
        frame.put((byte) FrameLimits.CODEC_ARROW_IPC);
        frame.put((byte) FrameLimits.COMPRESSION_ZSTD);
        frame.putInt(n);
        frame.putInt(directoryIds.length);
        frame.putInt(directoryLength);
        frame.putInt(compressed.length);
        frame.putInt(raw.length);
        for (int i = 0; i < directoryIds.length; i++) {
            frame.putLong(directoryIds[i]);
            Varint.write(frame, externalIds[i].length);
            frame.put(externalIds[i]);
        }
        frame.put(compressed);
        return frame.array();
    }

    private void expect(DatapointValueType expected) {
        if (type != expected) {
            throw new IllegalStateException("writer is for " + type + ", not " + expected);
        }
    }

    private int row(long id, long timestamp) {
        if (size == ids.length) {
            int grown = size * 2;
            ids = Arrays.copyOf(ids, grown);
            timestamps = Arrays.copyOf(timestamps, grown);
            if (longs != null) longs = Arrays.copyOf(longs, grown);
            if (doubles != null) doubles = Arrays.copyOf(doubles, grown);
            if (floats != null) floats = Arrays.copyOf(floats, grown);
            if (texts != null) texts = Arrays.copyOf(texts, grown);
        }
        ids[size] = id;
        timestamps[size] = timestamp;
        return size++;
    }

    private static byte[] textBytes(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("text value is empty");
        }
        if (value.length() > FrameLimits.MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("text value is " + value.length() + " characters, allowed " + FrameLimits.MAX_VALUE_CHARS);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FrameLimits.MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("text value is " + bytes.length + " bytes, allowed " + FrameLimits.MAX_VALUE_BYTES);
        }
        return bytes;
    }

    private int[] sortedOrder() {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        mergeSort(order, new int[size], 0, size);
        return order;
    }

    // A stable merge sort on the row index, so a repeated (id, timestamp) keeps insertion order and
    // the last one added wins in dedupeKeepingLast.
    private void mergeSort(int[] a, int[] tmp, int from, int to) {
        if (to - from < 2) {
            return;
        }
        int mid = (from + to) >>> 1;
        mergeSort(a, tmp, from, mid);
        mergeSort(a, tmp, mid, to);
        if (compare(a[mid - 1], a[mid]) <= 0) {
            return;
        }
        int i = from, j = mid, k = from;
        while (i < mid && j < to) {
            tmp[k++] = compare(a[i], a[j]) <= 0 ? a[i++] : a[j++];
        }
        while (i < mid) tmp[k++] = a[i++];
        while (j < to) tmp[k++] = a[j++];
        System.arraycopy(tmp, from, a, from, to - from);
    }

    private int compare(int x, int y) {
        int c = Long.compare(ids[x], ids[y]);
        return c != 0 ? c : Long.compare(timestamps[x], timestamps[y]);
    }

    private int dedupeKeepingLast(int[] order) {
        int n = 0;
        for (int i = 0; i < order.length; i++) {
            boolean lastOfKey = i + 1 == order.length || compare(order[i], order[i + 1]) != 0;
            if (lastOfKey) {
                order[n++] = order[i];
            }
        }
        return n;
    }

    private long[] distinctIds(int[] order, int n) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (i == 0 || ids[order[i]] != ids[order[i - 1]]) {
                count++;
            }
        }
        long[] distinct = new long[count];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (i == 0 || ids[order[i]] != ids[order[i - 1]]) {
                distinct[k++] = ids[order[i]];
            }
        }
        return distinct;
    }

    private ColumnData[] columns(int[] order, int n) {
        ByteBuffer idBuf = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer tsBuf = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            idBuf.putLong(ids[order[i]]);
            tsBuf.putLong(timestamps[order[i]]);
        }
        idBuf.flip();
        tsBuf.flip();
        ColumnData idCol = new ColumnData(null, 0, null, idBuf);
        ColumnData tsCol = new ColumnData(null, 0, null, tsBuf);
        return switch (type) {
            case BIGINT -> new ColumnData[]{idCol, tsCol, longColumn(order, n)};
            case FLOAT -> new ColumnData[]{idCol, tsCol, doubleColumn(order, n, null)};
            case FLOAT32 -> {
                ByteBuffer v = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) v.putFloat(floats[order[i]]);
                v.flip();
                yield new ColumnData[]{idCol, tsCol, new ColumnData(null, 0, null, v)};
            }
            case NUMERIC, DECIMAL32 -> {
                ByteBuffer v = ByteBuffer.allocate(n * 16).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) {
                    long unscaled = longs[order[i]];
                    v.putLong(unscaled);
                    v.putLong(unscaled >> 63);
                }
                v.flip();
                yield new ColumnData[]{idCol, tsCol, new ColumnData(null, 0, null, v)};
            }
            case TEXT -> new ColumnData[]{idCol, tsCol, textColumn(order, n, null)};
            case MIXED -> {
                byte[] numericBits = new byte[(n + 7) >>> 3];
                byte[] textBits = new byte[(n + 7) >>> 3];
                for (int i = 0; i < n; i++) {
                    if (texts[order[i]] == null) {
                        numericBits[i >>> 3] |= (byte) (1 << (i & 7));
                    } else {
                        textBits[i >>> 3] |= (byte) (1 << (i & 7));
                    }
                }
                yield new ColumnData[]{idCol, tsCol, doubleColumn(order, n, numericBits), textColumn(order, n, textBits)};
            }
        };
    }

    private ColumnData longColumn(int[] order, int n) {
        ByteBuffer v = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) v.putLong(longs[order[i]]);
        v.flip();
        return new ColumnData(null, 0, null, v);
    }

    private ColumnData doubleColumn(int[] order, int n, byte[] validity) {
        ByteBuffer v = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        int nulls = 0;
        for (int i = 0; i < n; i++) {
            boolean set = validity == null || (validity[i >>> 3] >>> (i & 7) & 1) != 0;
            v.putDouble(set ? doubles[order[i]] : 0d);
            if (!set) nulls++;
        }
        v.flip();
        return new ColumnData(validity == null ? null : ByteBuffer.wrap(validity), nulls, null, v);
    }

    private ColumnData textColumn(int[] order, int n, byte[] validity) {
        ByteBuffer offsets = ByteBuffer.allocate((n + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        int total = 0;
        int nulls = 0;
        for (int i = 0; i < n; i++) {
            byte[] t = texts[order[i]];
            if (t != null) total += t.length; else nulls++;
        }
        ByteBuffer data = ByteBuffer.allocate(total);
        int offset = 0;
        offsets.putInt(0);
        for (int i = 0; i < n; i++) {
            byte[] t = texts[order[i]];
            if (t != null) {
                data.put(t);
                offset += t.length;
            }
            offsets.putInt(offset);
        }
        offsets.flip();
        data.flip();
        return new ColumnData(validity == null ? null : ByteBuffer.wrap(validity), nulls, offsets, data);
    }
}
