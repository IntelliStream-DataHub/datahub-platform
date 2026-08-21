// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for recovering the original external id from a trashed file's DELETED_ name. */
class FileSystemServiceRestoreTest {

    @Test
    void recoversTheOriginalExternalIdBetweenChecksumAndEpoch() {
        // DELETED_<checksumHex>_<originalExternalId>_<epochMillis>
        assertEquals("myfile",
                FileSystemService.recoverOriginalExternalId("DELETED_abc123_myfile_1783494804120"));
    }

    @Test
    void keepsUnderscoresInsideTheOriginalExternalId() {
        assertEquals("my_file_name",
                FileSystemService.recoverOriginalExternalId("DELETED_abc123_my_file_name_1783494804120"));
    }

    @Test
    void handlesTheMinimalThreeSegmentShape() {
        assertEquals("a", FileSystemService.recoverOriginalExternalId("DELETED_c_a_1783494804120"));
    }

    @Test
    void returnsNullForUnrecognizedShapes() {
        assertNull(FileSystemService.recoverOriginalExternalId(null));
        assertNull(FileSystemService.recoverOriginalExternalId("not_a_deleted_id"));       // no DELETED_ prefix
        assertNull(FileSystemService.recoverOriginalExternalId("DELETED_onlychecksum"));    // no second underscore
    }
}
