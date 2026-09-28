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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeletedFilePurgeTaskTest {

    private static final VaultProperties VAULT =
            VaultProperties.of("http://vault.invalid:8200", "test", "test");

    private static final Instant OLD = Instant.now().minus(40, ChronoUnit.DAYS);   // > 30d grace
    private static final Instant RECENT = Instant.now();

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
        TenantConfigService svc = new TenantConfigService(null, null, VAULT, null);
        svc.cachedTenants.put(t.getOrganizationId(), t);
        return svc;
    }

    @Test
    void purgesOnlyFilesPastTheGrace(@TempDir Path trash) throws Exception {
        Path oldFile = Files.writeString(trash.resolve("1"), "x");
        Path freshFile = Files.writeString(trash.resolve("2"), "y");

        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(
                new TrashedNode(1, "1", OLD), new TrashedNode(2, "2", RECENT)));

        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, new FileCleanupProperties())
                .purgeExpiredTrash();

        assertFalse(Files.exists(oldFile), "file past the grace must be deleted from trash");
        assertTrue(Files.exists(freshFile), "file within the grace must be kept (restorable)");
        verify(purger).hardDelete(1L);
        verify(purger, never()).hardDelete(2L);
    }

    @Test
    void purgesAFileTrashedUnderItsOldTombstoneName(@TempDir Path trash) throws Exception {
        // Files trashed before V45 keep their DELETED_… tombstone as trash_name.
        String tombstone = "DELETED_abc_myfile_1783494804120";
        Path oldFile = Files.writeString(trash.resolve(tombstone), "x");
        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(new TrashedNode(1, tombstone, OLD)));

        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, new FileCleanupProperties())
                .purgeExpiredTrash();

        assertFalse(Files.exists(oldFile));
        verify(purger).hardDelete(1L);
    }

    @Test
    void purgesAFolderRowWithNothingInTheTrash(@TempDir Path trash) {
        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(new TrashedNode(3, null, OLD)));

        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, new FileCleanupProperties())
                .purgeExpiredTrash();

        verify(purger).hardDelete(3L);
    }

    @Test
    void dryRunDeletesNothing(@TempDir Path trash) throws Exception {
        Path oldFile = Files.writeString(trash.resolve("1"), "x");
        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(new TrashedNode(1, "1", OLD)));

        FileCleanupProperties props = new FileCleanupProperties();
        props.setDryRun(true);
        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, props).purgeExpiredTrash();

        assertTrue(Files.exists(oldFile), "dry-run must not delete the file");
        verify(purger, never()).hardDelete(anyLong());
    }

    @Test
    void refusesToUnlinkOutsideTheTrashDir(@TempDir Path base) throws Exception {
        Path trash = Files.createDirectories(base.resolve("trash"));
        Path secret = Files.writeString(base.resolve("secret"), "keep me");
        TrashPurger purger = mock(TrashPurger.class);
        when(purger.findTrashed()).thenReturn(List.of(new TrashedNode(9, "../secret", OLD)));

        new DeletedFilePurgeTask(serviceWith(tenant(trash)), purger, new FileCleanupProperties())
                .purgeExpiredTrash();

        assertTrue(Files.exists(secret), "a path escaping the trash dir must never be unlinked");
        verify(purger, never()).hardDelete(anyLong());
    }
}
