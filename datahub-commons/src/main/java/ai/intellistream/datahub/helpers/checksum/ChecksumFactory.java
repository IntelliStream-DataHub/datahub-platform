// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.checksum;

import org.apache.commons.codec.digest.Blake3;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Creates a fresh {@link FileChecksum} for each file, for a fixed {@link ChecksumAlgorithm}. Every
 * supported algorithm streams the file chunk by chunk, so a caller never has to hold the whole file
 * in memory.
 *
 * <p>This is a plain (Spring-free) factory so it can be reused across modules; the API module wires
 * it as a bean from its configured algorithm. The SHA family is served by {@link MessageDigest},
 * which the JVM hardware-accelerates via the CPU's SHA extensions (SHA-NI on x86, the crypto
 * extensions on AArch64); BLAKE3 uses the (pure-Java) commons-codec implementation.
 */
public class ChecksumFactory {

    private final ChecksumAlgorithm algorithm;

    public ChecksumFactory(ChecksumAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public ChecksumAlgorithm algorithm() {
        return algorithm;
    }

    /** A new, single-use checksum for one file. */
    public FileChecksum create() {
        return switch (algorithm) {
            case SHA_256, SHA_1, SHA_512 -> new MessageDigestChecksum(algorithm.jcaName());
            case BLAKE3 -> new Blake3Checksum();
        };
    }

    /** SHA-1 / SHA-256 / SHA-512 via JCA; {@code MessageDigest.update} uses the CPU SHA intrinsics. */
    private static final class MessageDigestChecksum implements FileChecksum {

        private final MessageDigest digest;

        MessageDigestChecksum(String jcaName) {
            try {
                this.digest = MessageDigest.getInstance(jcaName);
            } catch (NoSuchAlgorithmException e) {
                // The SHA family is mandated by the Java spec, so this should never happen.
                throw new IllegalStateException("MessageDigest algorithm unavailable: " + jcaName, e);
            }
        }

        @Override
        public void update(byte[] input, int offset, int length) {
            digest.update(input, offset, length);
        }

        @Override
        public byte[] digest() {
            return digest.digest();
        }
    }

    /** BLAKE3 (256-bit) via commons-codec. */
    private static final class Blake3Checksum implements FileChecksum {

        private final Blake3 blake3 = Blake3.initHash();

        @Override
        public void update(byte[] input, int offset, int length) {
            blake3.update(input, offset, length);
        }

        @Override
        public byte[] digest() {
            byte[] out = new byte[32];
            blake3.doFinalize(out);
            return out;
        }
    }
}
