// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import jakarta.validation.constraints.NotBlank;

import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ClickHouseHelper {

    private final static int SCALE = 6;

    private final static int DECIMAL32_SCALE = 4;
    // Decimal32(4) == Decimal(9, 4): 9 significant digits, so the largest representable magnitude is
    // +/-99999.9999. The producer clamps to this range (and logs) before encoding.
    public static final BigDecimal DECIMAL32_MAX = new BigDecimal("99999.9999");
    public static final BigDecimal DECIMAL32_MIN = DECIMAL32_MAX.negate();

    // Helper: Writes a ClickHouse-compatible String
    public static void writeString(DataOutputStream dos, String s) throws IOException {
        if (s == null || s.isEmpty()) {
            dos.writeByte(0); // Length 0
            return;
        }

        // 1. Get UTF-8 Bytes
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);

        // 2. Write Length as LEB128 (VarInt)
        writeUnsignedLeb128(dos, bytes.length);

        // 3. Write Data
        dos.write(bytes);
    }

    // ClickHouse standard integer encoding (VarInt)
    public static void writeUnsignedLeb128(DataOutputStream dos, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            dos.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dos.writeByte(value & 0x7F);
    }

    public static void writeNumeric6(DataOutputStream dos, @NotBlank String value) throws IOException {
        BigDecimal bd = new BigDecimal(value);
        // Scale it up for Decimal64(6)
        // movePointRight(6) is equivalent to multiplying by 10^6
        // setScale(0, ...) ensures we cut off any extra decimals beyond 6 places safely
        BigDecimal scaled = bd.movePointRight(SCALE).setScale(0, RoundingMode.HALF_UP);

        // Get the primitive long value
        long rawValue = scaled.longValue();

        // Write to stream (Little Endian)
        dos.writeLong(Long.reverseBytes(rawValue));
    }

    /**
     * Write a value as the raw Int32 of a ClickHouse Decimal32(4) column. Digits past the 4th
     * decimal place are rounded half-up (this applies to every value — it is how a value is fit into
     * 4 fractional digits). The value must already be within {@link #DECIMAL32_MIN}..{@link
     * #DECIMAL32_MAX}; the caller clamps and logs out-of-range magnitudes first.
     */
    public static void writeDecimal32(DataOutputStream dos, BigDecimal value) throws IOException {
        int rawValue = value.movePointRight(DECIMAL32_SCALE).setScale(0, RoundingMode.HALF_UP).intValue();
        dos.writeInt(Integer.reverseBytes(rawValue));
    }

    public static void writeUuid(DataOutputStream dos, UUID uuid) throws IOException {
        if (uuid == null) {
            // UUIDs are a strictly fixed 16-byte length in RowBinary.
            // If the column isn't a Nullable(UUID), writing 16 bytes of zeros
            // acts as the default "Empty/Zero UUID".
            dos.writeLong(0L);
            dos.writeLong(0L);
            return;
        }

        // ClickHouse requires each 8-byte half of the UUID to be reversed.
        // Long.reverseBytes() flips the bits, and dos.writeLong() (which is naturally
        // big-endian) writes those flipped bits out, resulting in the exact
        // little-endian byte sequence ClickHouse expects.
        dos.writeLong(Long.reverseBytes(uuid.getMostSignificantBits()));
        dos.writeLong(Long.reverseBytes(uuid.getLeastSignificantBits()));
    }

    // Helper: Writes a ClickHouse-compatible Int128 from BigInteger
    public static void writeInt128(DataOutputStream dos, BigInteger value) throws IOException {
        if (value == null) {
            // Default to 0 if null (assuming the column is not Nullable(Int128))
            dos.writeLong(0L);
            dos.writeLong(0L);
            return;
        }

        // 1. Extract the lower 64 bits (Least Significant Bits)
        long lower = value.longValue();

        // 2. Extract the upper 64 bits (Most Significant Bits)
        // BigInteger.shiftRight() performs a signed shift, which perfectly
        // preserves the sign-extension needed for negative numbers.
        long upper = value.shiftRight(64).longValue();

        // 3. Write them in true Little-Endian order (Lower half first, then Upper half)
        // We still use Long.reverseBytes() to flip Java's native big-endian long output.
        dos.writeLong(Long.reverseBytes(lower));
        dos.writeLong(Long.reverseBytes(upper));
    }

    public static void writeArrayInt64(DataOutputStream dos, List<Long> list) throws IOException {
        // If the list is null or empty, the array size is simply 0.
        if (list == null || list.isEmpty()) {
            dos.writeByte(0);
            return;
        }

        // 1. Write the number of elements in the array as a VarInt (LEB128)
        writeUnsignedLeb128(dos, list.size());

        // 2. Write each element sequentially
        for (Long value : list) {
            // If your list happens to contain nulls, default to 0L.
            // (Unless your column is strictly Array(Nullable(Int64)), in which
            // case you'd need a null-flag byte before each element).
            long safeValue = (value == null) ? 0L : value;

            // Write as little-endian 64-bit integer
            dos.writeLong(Long.reverseBytes(safeValue));
        }
    }

    // Helper: Writes a ClickHouse-compatible Array(String)
    public static void writeArrayString(DataOutputStream dos, List<String> list) throws IOException {
        if (list == null || list.isEmpty()) {
            dos.writeByte(0); // Array length 0
            return;
        }

        // 1. Write the number of elements in the array as a VarInt (LEB128)
        writeUnsignedLeb128(dos, list.size());

        // 2. Write each String element
        for (String s : list) {
            // Reuse your existing helper!
            // Note: Your helper already beautifully handles nulls by converting
            // them to empty strings (writing a 0 length byte), which perfectly
            // matches ClickHouse's requirement for non-Nullable String columns.
            writeString(dos, s);
        }
    }

    // Helper: Writes a ClickHouse-compatible Map(String, String)
    public static void writeMapStringString(DataOutputStream dos, Map<String, String> map) throws IOException {
        if (map == null || map.isEmpty()) {
            dos.writeByte(0); // Map size 0
            return;
        }

        // 1. Write the number of key-value pairs as a VarInt (LEB128)
        writeUnsignedLeb128(dos, map.size());

        // 2. Iterate through the map and write each Entry (Tuple)
        for (Map.Entry<String, String> entry : map.entrySet()) {
            // Note: ClickHouse Maps do not allow null keys.
            if(entry.getKey() != null){
                // Write the Key
                writeString(dos, entry.getKey());

                // Write the Value
                writeString(dos, entry.getValue());
            }
        }
    }
}
