// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.checksum;

/**
 * Checksum algorithms supported for uploaded files. Selected via
 * {@code datahub.files.checksum.algorithm}; Spring's relaxed binding accepts the conventional
 * spellings too, e.g. {@code SHA-256} maps to {@link #SHA_256}.
 */
public enum ChecksumAlgorithm {

    SHA_256("SHA-256"),
    SHA_1("SHA-1"),
    SHA_512("SHA-512"),
    /** Not backed by a JCA {@code MessageDigest}; handled via commons-codec. */
    BLAKE3(null);

    private final String jcaName;

    ChecksumAlgorithm(String jcaName) {
        this.jcaName = jcaName;
    }

    /** JCA {@code MessageDigest} algorithm name, or {@code null} for non-MessageDigest algorithms. */
    public String jcaName() {
        return jcaName;
    }
}
