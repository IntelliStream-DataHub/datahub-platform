// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.checksum;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChecksumFactoryTest {

    /** Hash the given chunks (each fed via a separate update) with a fresh checksum for the algorithm. */
    private static byte[] digest(ChecksumAlgorithm algorithm, byte[]... chunks) {
        FileChecksum checksum = new ChecksumFactory(algorithm).create();
        for (byte[] chunk : chunks) {
            checksum.update(chunk, 0, chunk.length);
        }
        return checksum.digest();
    }

    @Test
    void create_producesExpectedDigestLengthPerAlgorithm() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        assertEquals(20, digest(ChecksumAlgorithm.SHA_1, data).length);
        assertEquals(32, digest(ChecksumAlgorithm.SHA_256, data).length);
        assertEquals(64, digest(ChecksumAlgorithm.SHA_512, data).length);
        assertEquals(32, digest(ChecksumAlgorithm.BLAKE3, data).length);
    }

    @Test
    void sha256_matchesKnownVector() {
        byte[] out = digest(ChecksumAlgorithm.SHA_256, "test".getBytes(StandardCharsets.UTF_8));
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                HexFormat.of().formatHex(out));
    }

    @Test
    void shaFamily_matchesJdkOneShot() throws Exception {
        byte[] data = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(MessageDigest.getInstance("SHA-1").digest(data),
                digest(ChecksumAlgorithm.SHA_1, data));
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data),
                digest(ChecksumAlgorithm.SHA_256, data));
        assertArrayEquals(MessageDigest.getInstance("SHA-512").digest(data),
                digest(ChecksumAlgorithm.SHA_512, data));
    }

    @Test
    void chunkedUpdates_equalSingleUpdate() {
        // Streaming the file in chunks (as the upload does) must yield the same checksum as one shot.
        byte[] full = new byte[5000];
        for (int i = 0; i < full.length; i++) {
            full[i] = (byte) (i * 31 + 7);
        }
        byte[] c1 = Arrays.copyOfRange(full, 0, 1000);
        byte[] c2 = Arrays.copyOfRange(full, 1000, 1731);
        byte[] c3 = Arrays.copyOfRange(full, 1731, 5000);
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            assertArrayEquals(digest(algorithm, full), digest(algorithm, c1, c2, c3),
                    "chunked vs single-update differ for " + algorithm);
        }
    }

    @Test
    void update_respectsOffsetAndLength() {
        byte[] padded = new byte[]{9, 9, 't', 'e', 's', 't', 9};
        FileChecksum checksum = new ChecksumFactory(ChecksumAlgorithm.SHA_256).create();
        checksum.update(padded, 2, 4); // only the "test" slice
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                HexFormat.of().formatHex(checksum.digest()));
    }

    @Test
    void digestsAreDeterministic() {
        byte[] data = "repeatable".getBytes(StandardCharsets.UTF_8);
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            assertArrayEquals(digest(algorithm, data), digest(algorithm, data),
                    "non-deterministic digest for " + algorithm);
        }
    }

    @Test
    void create_returnsIndependentInstances() throws Exception {
        ChecksumFactory factory = new ChecksumFactory(ChecksumAlgorithm.SHA_256);
        FileChecksum a = factory.create();
        FileChecksum b = factory.create();
        assertNotSame(a, b);

        // Polluting one instance must not affect another: b, never updated, must equal the
        // digest of empty input.
        byte[] data = "datahub".getBytes(StandardCharsets.UTF_8);
        a.update(data, 0, data.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(new byte[0]), b.digest());
    }

    @Test
    void algorithm_reflectsConfiguredValue() {
        assertSame(ChecksumAlgorithm.BLAKE3, new ChecksumFactory(ChecksumAlgorithm.BLAKE3).algorithm());
    }
}
