// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.checksum;

/**
 * A streaming checksum over a single uploaded file: fed the file in chunks during the upload,
 * then finalized once. Instances are stateful and single-use, so they must not be shared between
 * uploads. Obtain a fresh one per file from {@link ChecksumFactory#create()}.
 */
public interface FileChecksum {

    /** Feed {@code length} bytes starting at {@code offset} into the checksum. */
    void update(byte[] input, int offset, int length);

    /** Finalize and return the raw checksum bytes. The instance must not be reused afterwards. */
    byte[] digest();
}
