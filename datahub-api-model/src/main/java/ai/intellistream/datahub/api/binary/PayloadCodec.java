// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

/**
 * The per-frame payload compression. The contract requires zstd; the level is the sender's choice.
 * Kept behind an interface so this module carries no native library: the SDK, the API and the
 * consumer supply {@link ZstdPayloadCodec} with zstd-jni on their own classpath.
 */
public interface PayloadCodec {

    byte[] compress(byte[] raw);

    /**
     * Decompresses to exactly {@code rawLength} bytes and throws {@link IllegalArgumentException}
     * when the stream ends early, runs long, or disagrees with the declared size.
     */
    byte[] decompress(byte[] compressed, int rawLength);
}
