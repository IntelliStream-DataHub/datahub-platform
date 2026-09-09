// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Restoring a file trashed before V47, when delete rewrote the external id to
 * {@code DELETED_<checksum>_<originalId>_<epochMillis>} and named the trash file after it.
 *
 * <p>Nothing writes that shape any more — delete records {@code deleted_at} and leaves the external
 * id alone — but entries already in the trash when the migration ran still carry it, and would be
 * unrestorable if this parsing went away with the scheme. These cases go when the trash does.
 */
class FileSystemServiceRestoreTest {

    @Test
    void recoversTheOriginalExternalIdBetweenChecksumAndEpoch() {
        // DELETED_<checksumHex>_<originalExternalId>_<epochMillis>
        assertEquals("myfile",
                FileSystemService.recoverLegacyOriginalExternalId("DELETED_abc123_myfile_1783494804120"));
    }

    @Test
    void keepsUnderscoresInsideTheOriginalExternalId() {
        assertEquals("my_file_name",
                FileSystemService.recoverLegacyOriginalExternalId("DELETED_abc123_my_file_name_1783494804120"));
    }

    @Test
    void handlesTheMinimalThreeSegmentShape() {
        assertEquals("a", FileSystemService.recoverLegacyOriginalExternalId("DELETED_c_a_1783494804120"));
    }

    @Test
    void returnsNullForUnrecognizedShapes() {
        assertNull(FileSystemService.recoverLegacyOriginalExternalId(null));
        assertNull(FileSystemService.recoverLegacyOriginalExternalId("not_a_deleted_id"));       // no DELETED_ prefix
        assertNull(FileSystemService.recoverLegacyOriginalExternalId("DELETED_onlychecksum"));    // no second underscore
    }

    /**
     * Anything deleted since V47 keeps its own external id, so the parser must decline it and let
     * restore take the current path. Verbatim storage makes the confusing case reachable: a caller
     * may now send an id that looks like a tombstone, where the old slug rewrite lowercased it.
     */
    @Test
    void declinesAnythingWrittenSinceTheSchemeChanged() {
        assertNull(FileSystemService.recoverLegacyOriginalExternalId("COM-99-PT-1034"));
        assertNull(FileSystemService.recoverLegacyOriginalExternalId("report_2026_q2"));
    }
}
