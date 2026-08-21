// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.BIGINT;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.DECIMAL32;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.FLOAT;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.FLOAT32;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.MIXED;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.NUMERIC;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.TEXT;

/**
 * Encodes a datapoint's textual value into the exact ClickHouse RowBinary value-bytes for its type,
 * and decodes those bytes back to a string.
 *
 * <p>This is the core of the binary all-datapoints transport: the producer encodes the value once,
 * the consumer streams the bytes straight into ClickHouse (no re-parse) and decodes them to a string
 * only for the fanned-out subset that goes to WebSocket subscribers. Every per-type binary detail —
 * little-endian layout, Decimal scaling, the DECIMAL32 round/clamp, and the MIXED two-column layout
 * (value_numeric Nullable(Float64) + value_text) — lives here so the producer, the consumer's
 * ClickHouse write, and the fan-out decode can never drift apart.
 *
 * <p>The bytes produced here are precisely the per-value portion {@code ClickHouseDatapointService}
 * writes today, so a round-trip is loss-free apart from the deliberate DECIMAL32 round/clamp.
 */
@Slf4j
public final class DatapointValueCodec {

    private DatapointValueCodec() {}

    /** Encode a value into its ClickHouse RowBinary bytes for {@code valueTypeId}. */
    public static byte[] encode(String value, int valueTypeId) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            switch (valueTypeId) {
                case BIGINT -> dos.writeLong(Long.reverseBytes(Long.parseLong(value)));
                case FLOAT -> dos.writeLong(Long.reverseBytes(Double.doubleToLongBits(Double.parseDouble(value))));
                case FLOAT32 -> dos.writeInt(Integer.reverseBytes(Float.floatToIntBits(Float.parseFloat(value))));
                case NUMERIC -> ClickHouseHelper.writeNumeric6(dos, value);
                case TEXT -> ClickHouseHelper.writeString(dos, value);
                case DECIMAL32 -> ClickHouseHelper.writeDecimal32(dos, clampDecimal32(value));
                case MIXED -> encodeMixed(dos, value);
                default -> throw new IllegalArgumentException("Unsupported value type id: " + valueTypeId);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    /** Decode ClickHouse RowBinary value-bytes back to a string (for subscription fan-out). */
    public static String decodeToString(byte[] bytes, int valueTypeId) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return switch (valueTypeId) {
                case BIGINT -> Long.toString(Long.reverseBytes(in.readLong()));
                case FLOAT -> Double.toString(Double.longBitsToDouble(Long.reverseBytes(in.readLong())));
                case FLOAT32 -> Float.toString(Float.intBitsToFloat(Integer.reverseBytes(in.readInt())));
                case NUMERIC -> BigDecimal.valueOf(Long.reverseBytes(in.readLong()), 6).toPlainString();
                case DECIMAL32 -> BigDecimal.valueOf(Integer.reverseBytes(in.readInt()), 4).toPlainString();
                case TEXT -> readString(in);
                case MIXED -> decodeMixed(in);
                default -> throw new IllegalArgumentException("Unsupported value type id: " + valueTypeId);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Round half-up to 4 decimals and clamp the magnitude to Decimal32(4)'s range, logging once. */
    private static BigDecimal clampDecimal32(String value) {
        BigDecimal bd = new BigDecimal(value);
        if (bd.compareTo(ClickHouseHelper.DECIMAL32_MAX) > 0 || bd.compareTo(ClickHouseHelper.DECIMAL32_MIN) < 0) {
            BigDecimal clamped = bd.signum() > 0 ? ClickHouseHelper.DECIMAL32_MAX : ClickHouseHelper.DECIMAL32_MIN;
            log.warn("DECIMAL32 value {} is outside ±99999.9999; clamping to {}.", value, clamped.toPlainString());
            return clamped;
        }
        return bd;
    }

    // MIXED: exactly one of the two nullable columns is populated. Layout matches datapoints_mixed:
    // value_numeric Nullable(Float64) then value_text Nullable(String); RowBinary null flag is
    // 0 = present, 1 = NULL.
    private static void encodeMixed(DataOutputStream dos, String value) throws IOException {
        boolean numeric;
        try {
            new BigDecimal(value);
            numeric = true;
        } catch (NumberFormatException e) {
            numeric = false;
        }
        if (numeric) {
            dos.writeByte(0);                                                            // value_numeric present
            dos.writeLong(Long.reverseBytes(Double.doubleToLongBits(Double.parseDouble(value))));
            dos.writeByte(1);                                                            // value_text NULL
        } else {
            dos.writeByte(1);                                                            // value_numeric NULL
            dos.writeByte(0);                                                            // value_text present
            ClickHouseHelper.writeString(dos, value);
        }
    }

    private static String decodeMixed(DataInputStream in) throws IOException {
        if (in.readUnsignedByte() == 0) {                                                // value_numeric present
            return Double.toString(Double.longBitsToDouble(Long.reverseBytes(in.readLong())));
        }
        in.readUnsignedByte();                                                           // value_text present flag (0)
        return readString(in);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readUnsignedLeb128(in);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int readUnsignedLeb128(DataInputStream in) throws IOException {
        int result = 0, shift = 0, b;
        do {
            b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }
}
