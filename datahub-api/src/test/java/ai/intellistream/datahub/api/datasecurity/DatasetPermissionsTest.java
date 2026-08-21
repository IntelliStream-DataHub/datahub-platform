// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.authorities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DatasetPermissions} as a value object: blanket flags plus already-expanded dataset ids.
 * Resolving groups into flags and ids is {@link DatasetPermissionsResolver}'s job and is tested
 * there; the grammar for the group paths (including the {@code /datasets/*} wildcard) is tested in
 * {@link DatasetGrantsTest}.
 */
class DatasetPermissionsTest {

    private static DatasetPermissions blanket(boolean readAll, boolean writeAll) {
        return DatasetPermissions.of(readAll, writeAll, Set.of(), Set.of());
    }

    @Test
    void explicitIdsGrantAccessToExactlyThoseDatasets() {
        DatasetPermissions p = DatasetPermissions.of(false, false, Set.of(56L, 21L), Set.of(23L));

        assertTrue(p.canRead(56));
        assertTrue(p.canRead(21));
        assertFalse(p.canRead(99));
        assertTrue(p.canWrite(23));
        assertEquals(Set.of(56L, 21L), p.readableIds());
        assertEquals(Set.of(23L), p.writableIds());
    }

    /** Read and write stay strictly separate: a write grant confers no read. */
    @Test
    void writeDoesNotImplyRead() {
        DatasetPermissions p = DatasetPermissions.of(false, false, Set.of(), Set.of(23L, 57L));

        assertTrue(p.canWrite(23));
        assertFalse(p.canRead(23));
        assertTrue(p.readableIds().isEmpty());
    }

    @Test
    void readAllFlagGrantsReadEverythingOnly() {
        DatasetPermissions p = blanket(true, false);
        assertTrue(p.canReadEverything());
        assertTrue(p.canRead(123));
        assertFalse(p.canWriteEverything());
        assertFalse(p.canWrite(123));
    }

    @Test
    void writeAllFlagGrantsWriteEverythingOnly() {
        DatasetPermissions p = blanket(false, true);
        assertTrue(p.canWriteEverything());
        assertTrue(p.canWrite(123));
        assertFalse(p.canReadEverything());
        assertFalse(p.canRead(123));
    }

    /** What both wildcard groups — or the admin short-circuit — resolve to. */
    @Test
    void allDatasetsGrantsReadAndWriteEverything() {
        DatasetPermissions p = DatasetPermissions.allDatasets();
        assertTrue(p.canReadEverything());
        assertTrue(p.canWriteEverything());
        assertTrue(p.canRead(1));
        assertTrue(p.canWrite(1));
    }

    /** A blanket flag answers for every dataset, whatever the explicit id set holds. */
    @Test
    void blanketFlagsOverrideTheExplicitIds() {
        DatasetPermissions p = DatasetPermissions.of(true, false, Set.of(), Set.of());

        assertTrue(p.canRead(999));
        assertTrue(p.canReadDataSet(null));
    }

    /**
     * A null dataset id is an orphan entity, reachable only by a blanket grant. This is what stops
     * an entity with no dataset being visible to a caller with per-dataset grants only.
     */
    @Test
    void orphansNeedABlanketGrant() {
        assertFalse(DatasetPermissions.of(false, false, Set.of(1L), Set.of()).canReadDataSet(null));
        assertTrue(blanket(true, false).canReadDataSet(null));
    }

    // ---- the admin escape hatch ----------------------------------------------------------------

    @Test
    void adminIsRecognisedFromTheAuthorities() {
        assertTrue(DatasetPermissions.isAdmin(authorities("ROLE_DATAHUB_ADMIN")));
        assertTrue(DatasetPermissions.isAdmin(
                authorities("ROLE_DATAHUB_ACCESS", "ROLE_DATAHUB_ADMIN")));
    }

    /**
     * The retired blanket roles must not be recognised: after the move to wildcard organization
     * groups, a stale {@code DATAHUB_DATASET_ALL} role in someone's realm grants nothing.
     */
    @Test
    void unrelatedAndRetiredRolesAreNotAdmin() {
        assertFalse(DatasetPermissions.isAdmin(authorities("ROLE_DATAHUB_ACCESS")));
        assertFalse(DatasetPermissions.isAdmin(authorities("ROLE_DATAHUB_DATASET_ALL")));
        assertFalse(DatasetPermissions.isAdmin(authorities("ROLE_DATAHUB_DATASET_READ_ALL")));
        assertFalse(DatasetPermissions.isAdmin(authorities("ROLE_DATAHUB_DATASET_WRITE_ALL")));
        assertFalse(DatasetPermissions.isAdmin(List.of()));
        assertFalse(DatasetPermissions.isAdmin(null));
    }

    // ---- degenerate inputs ---------------------------------------------------------------------

    @Test
    void noneGrantsNothing() {
        DatasetPermissions p = DatasetPermissions.none();
        assertFalse(p.canReadEverything());
        assertFalse(p.canWriteEverything());
        assertFalse(p.canRead(1));
        assertFalse(p.canWrite(1));
    }

    @Test
    void handlesNullIdSets() {
        DatasetPermissions p = DatasetPermissions.of(false, false, null, null);
        assertFalse(p.canRead(1));
        assertTrue(p.readableIds().isEmpty());
        assertTrue(p.writableIds().isEmpty());
    }

    /** The id sets are copied on the way in and exposed unmodifiable, so a permission set is frozen. */
    @Test
    void idSetsAreDefensivelyCopiedAndImmutable() {
        Set<Long> mutable = new HashSet<>(Set.of(1L));
        DatasetPermissions p = DatasetPermissions.of(false, false, mutable, Set.of());

        mutable.add(2L);

        assertFalse(p.canRead(2));
        assertThrows(UnsupportedOperationException.class, () -> p.readableIds().add(3L));
    }
}
