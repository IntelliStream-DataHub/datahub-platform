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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempUploadCleanupTaskTest {

    private static final VaultProperties VAULT =
            VaultProperties.of("http://vault.invalid:8200", "test", "test");

    private static final String TMP = new FileCleanupProperties().getTempDirName(); // shared ".tmp"

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
        // Construct directly (no Spring) so @PostConstruct/Vault never runs; populate the cache.
        TenantConfigService svc = new TenantConfigService(null, null, VAULT);
        svc.cachedTenants.put(t.getOrganizationId(), t);
        return svc;
    }

    @Test
    void deletesStaleTempPartsButKeepsRecentOnes(@TempDir Path root) throws Exception {
        Path tmpDir = root.resolve(TMP);
        Files.createDirectories(tmpDir);
        Path stale = Files.writeString(tmpDir.resolve("stale.part"), "x");
        Path fresh = Files.writeString(tmpDir.resolve("fresh.part"), "y");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        new TempUploadCleanupTask(serviceWith(tenant("org-1", root.toString())), new FileCleanupProperties())
                .cleanStaleTempUploads();

        assertFalse(Files.exists(stale), "stale temp part (10d) should be deleted");
        assertTrue(Files.exists(fresh), "fresh temp part should be kept");
    }

    @Test
    void dryRunLogsButDeletesNothing(@TempDir Path root) throws Exception {
        Path tmpDir = root.resolve(TMP);
        Files.createDirectories(tmpDir);
        Path stale = Files.writeString(tmpDir.resolve("stale.part"), "x");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        FileCleanupProperties props = new FileCleanupProperties();
        props.setDryRun(true);
        new TempUploadCleanupTask(serviceWith(tenant("org-1", root.toString())), props)
                .cleanStaleTempUploads();

        assertTrue(Files.exists(stale), "dry-run must not delete the stale temp part");
    }

    @Test
    void onlyScansTheTenantRootTempDir(@TempDir Path root) throws Exception {
        // Stale entry in the single root staging dir → cleaned.
        Path rootTmp = Files.createDirectories(root.resolve(TMP));
        Path staleAtRoot = Files.writeString(rootTmp.resolve("stale.part"), "x");
        Files.setLastModifiedTime(staleAtRoot, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        // A stale entry in a NESTED dir (legacy layout) is NOT scanned — by design, no tree walk.
        Path nestedTmp = Files.createDirectories(root.resolve("sub").resolve(TMP));
        Path staleNested = Files.writeString(nestedTmp.resolve("legacy.part"), "x");
        Files.setLastModifiedTime(staleNested, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        // A normal stale file (not under a temp dir) is never touched.
        Path realFile = Files.writeString(root.resolve("keep.bin"), "z");
        Files.setLastModifiedTime(realFile, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        new TempUploadCleanupTask(serviceWith(tenant("org-1", root.toString())), new FileCleanupProperties())
                .cleanStaleTempUploads();

        assertFalse(Files.exists(staleAtRoot), "stale part in the root temp dir should be deleted");
        assertTrue(Files.exists(staleNested), "nested (legacy) temp dirs are not scanned");
        assertTrue(Files.exists(realFile), "a stale file not under a temp dir must never be touched");
    }
}
