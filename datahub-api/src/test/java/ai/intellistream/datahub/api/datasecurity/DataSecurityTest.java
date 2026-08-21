// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.granting;
import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.readingAndWritingEverything;
import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.writingEverything;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rules {@link DataSecurity} applies over an already-resolved permission set: read/write
 * separation, node-follows-dataset, and orphan handling. Resolving Keycloak groups into that set is
 * {@link DatasetPermissionsResolver}'s job and is tested there.
 */
class DataSecurityTest {

    private static NodeEntity nodeInDataset(Long datasetId) {
        NodeEntity node = mock(NodeEntity.class);
        if (datasetId == null) {
            when(node.getDataSet()).thenReturn(null);
        } else {
            DatasetEntity ds = mock(DatasetEntity.class);
            when(ds.getId()).thenReturn(datasetId);
            when(node.getDataSet()).thenReturn(ds);
        }
        return node;
    }

    @Test
    void readGrantAllowsReadDeniesWrite() {
        DataSecurity dataSecurity = granting(Set.of(56L, 21L), Set.of());

        assertTrue(dataSecurity.hasReadPermissionToDataSet(56));
        assertTrue(dataSecurity.hasReadPermissionToDataSet(21));
        assertFalse(dataSecurity.hasReadPermissionToDataSet(99));
        assertFalse(dataSecurity.hasWritePermissionToDataSet(56));
        assertEquals(Set.of(56L, 21L), dataSecurity.readableDataSetIds());
    }

    @Test
    void writeGrantAllowsWriteDeniesRead() {
        DataSecurity dataSecurity = granting(Set.of(), Set.of(23L, 57L, 154L));

        assertTrue(dataSecurity.hasWritePermissionToDataSet(23));
        assertFalse(dataSecurity.hasReadPermissionToDataSet(23));
        assertEquals(Set.of(23L, 57L, 154L), dataSecurity.writableDataSetIds());
    }

    @Test
    void nodeReadPermissionFollowsDataset() {
        DataSecurity dataSecurity = granting(Set.of(56L), Set.of());

        assertTrue(dataSecurity.hasReadPermissionToDataSet(nodeInDataset(56L)));
        assertFalse(dataSecurity.hasReadPermissionToDataSet(nodeInDataset(57L)));
    }

    @Test
    void orphanNodeReadableOnlyByReadAll() {
        assertFalse(granting(Set.of(56L), Set.of()).hasReadPermissionToDataSet(nodeInDataset(null)));
        assertTrue(readingAndWritingEverything().hasReadPermissionToDataSet(nodeInDataset(null)));
    }

    @Test
    void orphanNodeWritableOnlyByWriteAll() {
        assertFalse(granting(Set.of(), Set.of(56L)).hasWritePermissionToDataSet(nodeInDataset(null)));
        assertTrue(writingEverything().hasWritePermissionToDataSet(nodeInDataset(null)));
    }

    @Test
    void nullableDatasetChecksTreatNullAsOrphan() {
        DataSecurity dataSecurity = granting(Set.of(), Set.of(10L));
        assertTrue(dataSecurity.canWriteToDataSet(10L));
        assertFalse(dataSecurity.canWriteToDataSet(11L));
        assertFalse(dataSecurity.canWriteToDataSet(null));

        assertTrue(writingEverything().canWriteToDataSet(null));
    }

    @Test
    void assertCanWriteThrowsWhenDenied() {
        DataSecurity dataSecurity = granting(Set.of(), Set.of(10L));

        assertThrows(AccessDeniedException.class, () -> dataSecurity.assertCanWriteDataSet(11L));
        // Allowed dataset does not throw.
        dataSecurity.assertCanWriteDataSet(10L);
    }

    @Test
    void assertCanReadThrowsWhenDenied() {
        DataSecurity dataSecurity = granting(Set.of(10L), Set.of());

        assertThrows(AccessDeniedException.class, () -> dataSecurity.assertCanRead(nodeInDataset(11L)));
        dataSecurity.assertCanRead(nodeInDataset(10L));
    }

    @Test
    void adminCanReadAndWriteEverything() {
        DataSecurity dataSecurity = readingAndWritingEverything();

        assertTrue(dataSecurity.hasReadAccessToEverything());
        assertTrue(dataSecurity.hasWriteAccessToEverything());
        assertTrue(dataSecurity.hasReadPermissionToDataSet(999));
        assertTrue(dataSecurity.hasWritePermissionToDataSet(999));
    }

    @Test
    void noGrantsDeniesEverything() {
        DataSecurity dataSecurity = TestDataSecurity.grantingNothing();

        assertFalse(dataSecurity.hasReadPermissionToDataSet(1));
        assertFalse(dataSecurity.hasWritePermissionToDataSet(1));
        assertTrue(dataSecurity.readableDataSetIds().isEmpty());
    }

    /** A null node is never readable or writable, whatever the caller holds. */
    @Test
    void nullNodeIsDeniedEvenForAdmin() {
        DataSecurity dataSecurity = readingAndWritingEverything();

        assertFalse(dataSecurity.hasReadPermissionToDataSet((NodeEntity) null));
        assertFalse(dataSecurity.hasWritePermissionToDataSet((NodeEntity) null));
    }
}
