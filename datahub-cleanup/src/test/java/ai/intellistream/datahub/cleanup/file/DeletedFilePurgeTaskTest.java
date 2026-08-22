// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.file;

import ai.intellistream.datahub.config.VaultProperties;
import ai.intellistream.datahub.cleanup.file.TrashPurger.TrashedNode;
import ai.intellistream.datahub.tenant.FileStorage;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeletedFilePurgeTaskTest {

    private static final long OLD = Instant.now().minus(40, ChronoUnit.DAYS).toEpochMilli();   // > 30d grace
    private static final long RECENT = Instant.now().toEpochMilli();

    private static Tenant tenant(Path trash) {
        Tenant t = new Tenant();
        t.setOrganizationId("org-1");
        t.setOrganizationName("org-1");
        FileStorage fs = new FileStorage();
        fs.setTrashPath(trash.toString());
        t.setFileStorage(fs);
        return t;
    }

    private static TenantConfigService serviceWith(Tenant t) {
        TenantConfigService svc = new TenantConfigService(null, null, VaultProperties.of("http://vault.invalid:8200", "test", "test"));
        svc.cachedTenants.put(t.getOrganizationId(), t);
        return svc;
    }

    @Test
    void purgesOnlyFilesPastTheGrace(@TempDir Path trash) throws Exception {
        String oldId = "DELETED_abc_myfile_" + OLD;
        String freshId = "DELETED_def_other_" + RECENT;
        Path oldFile = Files.writeString(trash.resolve(oldId), "x");
        Path freshFile = Files.writeString(trash.resolve(freshId), "y");

        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(
                new TrashedNode(1, oldId), new TrashedNode(2, freshId)));

        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, new FileCleanupProperties())
                .purgeExpiredTrash();

        assertFalse(Files.exists(oldFile), "file past the grace must be deleted from trash");
        assertTrue(Files.exists(freshFile), "file within the grace must be kept (restorable)");
        verify(purger).hardDelete(1L);
        verify(purger, never()).hardDelete(2L);
    }

    @Test
    void dryRunDeletesNothing(@TempDir Path trash) throws Exception {
        String oldId = "DELETED_abc_myfile_" + OLD;
        Path oldFile = Files.writeString(trash.resolve(oldId), "x");
        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(new TrashedNode(1, oldId)));

        FileCleanupProperties props = new FileCleanupProperties();
        props.setDryRun(true);
        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, props).purgeExpiredTrash();

        assertTrue(Files.exists(oldFile), "dry-run must not delete the file");
        verify(purger, never()).hardDelete(anyLong());
    }

    @Test
    void leavesInodesWithAnUnparseableExternalId(@TempDir Path trash) {
        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(new TrashedNode(1, "DELETED_bad_noepoch")));

        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, new FileCleanupProperties())
                .purgeExpiredTrash();

        verify(purger, never()).hardDelete(anyLong());
    }

    @Test
    void refusesToUnlinkOutsideTheTrashDir(@TempDir Path base) throws Exception {
        Path trash = Files.createDirectories(base.resolve("trash"));
        Path secret = Files.writeString(base.resolve("secret_" + OLD), "keep me");
        // Parseable epoch (so it isn't skipped earlier) but the path escapes the trash dir.
        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(new TrashedNode(9, "../secret_" + OLD)));

        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, new FileCleanupProperties())
                .purgeExpiredTrash();

        assertTrue(Files.exists(secret), "a path escaping the trash dir must never be unlinked");
        verify(purger, never()).hardDelete(anyLong());
    }

    @Test
    void deletionEpochMillisParsesTheTrailingSegment() {
        assertEquals(1783494804120L, DeletedFilePurgeTask.deletionEpochMillis("DELETED_abc_my_file_name_1783494804120"));
        assertEquals(1783494804120L, DeletedFilePurgeTask.deletionEpochMillis("DELETED__folder_1783494804120"));
        assertNull(DeletedFilePurgeTask.deletionEpochMillis("DELETED_no_epoch_here"));
        assertNull(DeletedFilePurgeTask.deletionEpochMillis("nounderscore"));
        assertNull(DeletedFilePurgeTask.deletionEpochMillis("trailing_"));
        assertNull(DeletedFilePurgeTask.deletionEpochMillis(null));
    }
}
