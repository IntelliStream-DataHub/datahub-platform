// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import java.nio.ByteBuffer;

/** Unsigned LEB128, the length prefix ClickHouse and the frame directory use. */
final class Varint {

    private Varint() {
    }

    static int size(int value) {
        int n = 1;
        while ((value & 0xFFFFFF80) != 0) {
            value >>>= 7;
            n++;
        }
        return n;
    }

    static void write(ByteBuffer out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.put((byte) (value & 0x7F));
    }

    /** Reads at most five bytes; anything longer, or a value over {@code max}, is malformed. */
    static int read(ByteBuffer in, int max) {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!in.hasRemaining()) {
                throw new IllegalArgumentException("Truncated varint");
            }
            int b = in.get() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0 || result > max) {
                    throw new IllegalArgumentException("Varint " + (result & 0xFFFFFFFFL) + " exceeds " + max);
                }
                return result;
            }
        }
        throw new IllegalArgumentException("Varint longer than five bytes");
    }
}
