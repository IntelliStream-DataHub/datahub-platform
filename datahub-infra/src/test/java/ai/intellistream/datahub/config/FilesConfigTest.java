// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.FileStorage;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FilesConfigTest {

    @Mock
    private TenantConfigService tenantConfigService;

    @InjectMocks
    private FilesConfig filesConfig;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Tenant tenant(String rootPath, String trashPath) {
        Tenant t = new Tenant();
        t.setOrganizationId("org-1");
        t.setOrganizationName("wellsearch");
        if (rootPath != null || trashPath != null) {
            FileStorage fs = new FileStorage();
            fs.setRootPath(rootPath);
            fs.setTrashPath(trashPath);
            t.setFileStorage(fs);
        }
        return t;
    }

    @Test
    void resolvesPathsFromTenantFileStorage() {
        TenantContext.setTenantId("org-1");
        when(tenantConfigService.getConfig("org-1"))
                .thenReturn(tenant("/opt/files-root/root/wellsearch", "/opt/files-root/trash/wellsearch"));

        assertEquals(Path.of("/opt/files-root/root/wellsearch"), filesConfig.getRoot());
        assertEquals(Path.of("/opt/files-root/trash/wellsearch"), filesConfig.getTrash());
    }

    @Test
    void failsWithClearMessageWhenFileStorageMissing() {
        TenantContext.setTenantId("org-1");
        when(tenantConfigService.getConfig("org-1")).thenReturn(tenant(null, null));

        IllegalStateException ex = assertThrows(IllegalStateException.class, filesConfig::getRoot);
        assertTrue(ex.getMessage().contains("file-storage"), ex.getMessage());
        assertTrue(ex.getMessage().contains("org-1"), ex.getMessage());
    }

    @Test
    void failsWithClearMessageWhenRootPathBlank() {
        TenantContext.setTenantId("org-1");
        when(tenantConfigService.getConfig("org-1")).thenReturn(tenant("   ", "/opt/files-root/trash/wellsearch"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, filesConfig::getRoot);
        assertTrue(ex.getMessage().contains("root-path"), ex.getMessage());
    }

    @Test
    void failsWhenTenantUnknown() {
        TenantContext.setTenantId("ghost");
        when(tenantConfigService.getConfig("ghost")).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, filesConfig::getTrash);
        assertTrue(ex.getMessage().contains("ghost"), ex.getMessage());
    }
}
