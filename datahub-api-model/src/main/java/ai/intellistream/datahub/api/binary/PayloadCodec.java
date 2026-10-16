// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import java.util.Arrays;

/**
 * The frame payload compression. The contract requires zstd, either on the whole payload
 * ({@link FrameLimits#COMPRESSION_ZSTD}) or on each buffer of its record batches
 * ({@link FrameLimits#COMPRESSION_ARROW_ZSTD}); the level is the sender's choice. Kept behind an
 * interface so this module carries no native library: the SDK, the API and the consumer supply
 * {@link ZstdPayloadCodec} with zstd-jni on their own classpath.
 */
public interface PayloadCodec {

    byte[] compress(byte[] raw);

    /**
     * Decompresses to exactly {@code rawLength} bytes and throws {@link IllegalArgumentException}
     * when the stream ends early, runs long, or disagrees with the declared size.
     */
    byte[] decompress(byte[] compressed, int rawLength);

    /**
     * As {@link #decompress(byte[], int)} for the {@code length} bytes at {@code offset}: one
     * in-band compressed buffer of a record batch, in place in its body.
     */
    default byte[] decompress(byte[] compressed, int offset, int length, int rawLength) {
        return decompress(Arrays.copyOfRange(compressed, offset, offset + length), rawLength);
    }
}
