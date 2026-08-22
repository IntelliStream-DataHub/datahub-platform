// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.file;

import ai.intellistream.datahub.config.VaultProperties;
import ai.intellistream.datahub.tenant.FileStorage;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrphanTenantFolderCleanupTaskTest {

    private static final VaultProperties VAULT =
            VaultProperties.of("http://vault.invalid:8200", "test", "test");

    private static Tenant tenant(String id, String rootPath) {
        Tenant t = new Tenant();
        t.setOrganizationId(id);
        t.setOrganizationName(id);
        FileStorage fs = new FileStorage();
        fs.setRootPath(rootPath);
        t.setFileStorage(fs);
        return t;
    }

    private static TenantConfigService serviceWith(Tenant t) {
        TenantConfigService svc = new TenantConfigService(null, null, VAULT);
        svc.cachedTenants.put(t.getOrganizationId(), t);
        return svc;
    }

    @Test
    void quarantinesOrphanFolderButKeepsLiveTenant(@TempDir Path base) throws Exception {
        Path live = Files.createDirectories(base.resolve("wellsearch"));
        Path orphan = Files.createDirectories(base.resolve("gonetenant"));
        Files.writeString(orphan.resolve("file.txt"), "data");

        new OrphanTenantFolderCleanupTask(serviceWith(tenant("org-1", live.toString())), new FileCleanupProperties())
                .cleanOrphanTenantFolders();

        assertTrue(Files.exists(live), "current tenant folder must be kept");
        assertFalse(Files.exists(orphan), "orphan folder must be moved out of its original location");

        Path quarantine = base.resolve("_orphaned");
        assertTrue(Files.isDirectory(quarantine), "quarantine dir created");
        try (Stream<Path> s = Files.list(quarantine)) {
            assertTrue(
                    s.anyMatch(p -> p.getFileName().toString().startsWith("gonetenant__quarantined__")),
                    "orphan must be quarantined with a timestamped name");
        }
    }

    @Test
    void keepsLiveRootAndTrashWhenTheyShareAParent(@TempDir Path base) throws Exception {
        // The layout shipped by vault-seed.sh: <tenant>/files (root) and <tenant>/trash sit under the
        // same parent <tenant>. The root scan must not treat the live trash dir as an orphan, nor the
        // trash scan the live root dir — but a genuine orphan sibling must still be quarantined.
        Path tenantDir = base.resolve("foo");
        Path root = Files.createDirectories(tenantDir.resolve("files"));
        Path trash = Files.createDirectories(tenantDir.resolve("trash"));
        Path orphan = Files.createDirectories(tenantDir.resolve("gonetenant"));
        Files.writeString(orphan.resolve("file.txt"), "data");

        Tenant t = new Tenant();
        t.setOrganizationId("org-1");
        t.setOrganizationName("org-1");
        FileStorage fs = new FileStorage();
        fs.setRootPath(root.toString());
        fs.setTrashPath(trash.toString());
        t.setFileStorage(fs);
        TenantConfigService svc = new TenantConfigService(null, null, VAULT);
        svc.cachedTenants.put("org-1", t);

        new OrphanTenantFolderCleanupTask(svc, new FileCleanupProperties()).cleanOrphanTenantFolders();

        assertTrue(Files.exists(root), "live root must be kept even though live trash is its sibling");
        assertTrue(Files.exists(trash), "live trash must be kept even though live root is its sibling");
        assertFalse(Files.exists(orphan), "a genuine orphan sibling must still be quarantined");
        Path quarantine = tenantDir.resolve("_orphaned");
        assertTrue(Files.isDirectory(quarantine), "quarantine dir created");
        try (Stream<Path> s = Files.list(quarantine)) {
            assertTrue(
                    s.anyMatch(p -> p.getFileName().toString().startsWith("gonetenant__quarantined__")),
                    "orphan must be quarantined with a timestamped name");
        }
    }

    @Test
    void neverScansInsideALiveRootWhenTrashIsNestedInIt(@TempDir Path base) throws Exception {
        // Nothing validates the configured layout, so trash may be nested INSIDE the root
        // (root=<t>/files, trash=<t>/files/trash). The trash's parent is then the live root itself —
        // scanning it as an orphan-parent would quarantine every real data folder in the root.
        Path root = Files.createDirectories(base.resolve("files"));
        Path trash = Files.createDirectories(root.resolve("trash"));
        Path data1 = Files.createDirectories(root.resolve("uploads"));
        Path data2 = Files.createDirectories(root.resolve("documents"));
        Files.writeString(data1.resolve("file.txt"), "data");

        Tenant t = new Tenant();
        t.setOrganizationId("org-1");
        t.setOrganizationName("org-1");
        FileStorage fs = new FileStorage();
        fs.setRootPath(root.toString());
        fs.setTrashPath(trash.toString());
        t.setFileStorage(fs);
        TenantConfigService svc = new TenantConfigService(null, null, VAULT);
        svc.cachedTenants.put("org-1", t);

        new OrphanTenantFolderCleanupTask(svc, new FileCleanupProperties()).cleanOrphanTenantFolders();

        assertTrue(Files.exists(data1), "live tenant data inside the root must never be quarantined");
        assertTrue(Files.exists(data2), "live tenant data inside the root must never be quarantined");
        assertTrue(Files.exists(trash), "nested live trash must be kept");
        assertFalse(Files.isDirectory(root.resolve("_orphaned")), "no quarantine dir may appear inside a live root");
    }

    @Test
    void purgesExpiredQuarantineEntriesPastGrace(@TempDir Path base) throws Exception {
        Path live = Files.createDirectories(base.resolve("wellsearch"));
        Path quarantine = Files.createDirectories(base.resolve("_orphaned"));
        long expiredAt = Instant.now().minus(40, ChronoUnit.DAYS).toEpochMilli(); // > 30d grace
        long recentAt = Instant.now().toEpochMilli();
        Path expired = Files.createDirectories(quarantine.resolve("old__quarantined__" + expiredAt));
        Path recent = Files.createDirectories(quarantine.resolve("recent__quarantined__" + recentAt));

        new OrphanTenantFolderCleanupTask(serviceWith(tenant("org-1", live.toString())), new FileCleanupProperties())
                .cleanOrphanTenantFolders();

        assertFalse(Files.exists(expired), "quarantined folder past the 30d grace must be deleted");
        assertTrue(Files.exists(recent), "quarantined folder within grace must be kept");
    }

    @Test
    void dryRunQuarantinesAndPurgesNothing(@TempDir Path base) throws Exception {
        Path live = Files.createDirectories(base.resolve("wellsearch"));
        Path orphan = Files.createDirectories(base.resolve("gonetenant"));
        Files.writeString(orphan.resolve("file.txt"), "data");
        Path quarantine = Files.createDirectories(base.resolve("_orphaned"));
        long expiredAt = Instant.now().minus(40, ChronoUnit.DAYS).toEpochMilli(); // > 30d grace
        Path expired = Files.createDirectories(quarantine.resolve("old__quarantined__" + expiredAt));

        FileCleanupProperties props = new FileCleanupProperties();
        props.setDryRun(true);
        new OrphanTenantFolderCleanupTask(serviceWith(tenant("org-1", live.toString())), props)
                .cleanOrphanTenantFolders();

        assertTrue(Files.exists(orphan), "dry-run must not move the orphan into quarantine");
        assertTrue(Files.exists(expired), "dry-run must not purge the expired quarantine entry");
    }

    @Test
    void doesNothingWhenNoTenantsLoaded(@TempDir Path base) throws Exception {
        Path looksOrphan = Files.createDirectories(base.resolve("something"));

        // Empty tenant cache → no known paths → must not scan/delete anything.
        new OrphanTenantFolderCleanupTask(new TenantConfigService(null, null, VAULT),
                new FileCleanupProperties()).cleanOrphanTenantFolders();

        assertTrue(Files.exists(looksOrphan), "with no tenants loaded, nothing must be touched");
    }
}
