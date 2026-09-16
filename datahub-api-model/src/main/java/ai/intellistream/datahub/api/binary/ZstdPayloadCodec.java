// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import com.github.luben.zstd.Zstd;

/**
 * zstd through zstd-jni. Compile-only here; a module that uses this class puts
 * {@code com.github.luben:zstd-jni} on its runtime classpath.
 */
public final class ZstdPayloadCodec implements PayloadCodec {

    public static final int DEFAULT_LEVEL = 9;

    private final int level;

    public ZstdPayloadCodec(int level) {
        if (level != 1 && level != 3 && level != 9) {
            throw new IllegalArgumentException("zstd level must be 1, 3 or 9, was " + level);
        }
        this.level = level;
    }

    public ZstdPayloadCodec() {
        this(DEFAULT_LEVEL);
    }

    public int level() {
        return level;
    }

    @Override
    public byte[] compress(byte[] raw) {
        return Zstd.compress(raw, level);
    }

    @Override
    public byte[] decompress(byte[] compressed, int rawLength) {
        long declared = Zstd.decompressedSize(compressed);
        if (declared > 0 && declared != rawLength) {
            throw new IllegalArgumentException("zstd frame declares " + declared + " bytes, envelope says " + rawLength);
        }
        byte[] out = new byte[rawLength];
        long produced;
        try {
            produced = Zstd.decompressByteArray(out, 0, rawLength, compressed, 0, compressed.length);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("zstd payload is not decodable: " + e.getMessage(), e);
        }
        if (Zstd.isError(produced)) {
            throw new IllegalArgumentException("zstd payload is not decodable: " + Zstd.getErrorName(produced));
        }
        if (produced != rawLength) {
            throw new IllegalArgumentException("zstd payload decompressed to " + produced + " bytes, envelope says " + rawLength);
        }
        return out;
    }
}
