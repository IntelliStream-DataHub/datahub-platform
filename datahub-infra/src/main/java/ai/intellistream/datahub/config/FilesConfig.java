// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.FileStorage;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves per-tenant file-storage paths from the tenant's Vault {@code file-storage} block
 * ({@code root-path} / {@code trash-path}, absolute and tenant-specific). There is no global
 * fallback: a tenant without a usable file-storage configuration is a configuration error and
 * fails loudly with a descriptive message.
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class FilesConfig {

    private final TenantConfigService tenantConfigService;

    @PostConstruct
    public void init() {
        log.info("FilesConfig initialized; ensuring per-tenant file-storage directories exist.");
        try {
            for (var t : tenantConfigService.cachedTenants.values()) {
                TenantContext.setTenantId(t.getOrganizationId());
                try {
                    ensureDirectory("root", getRoot());
                    ensureDirectory("trash", getTrash());
                } catch (Exception e) {
                    // Don't let one misconfigured tenant block startup. The same error resurfaces
                    // if that tenant later performs a file operation.
                    log.error("File-storage setup skipped for tenant '{}': {}",
                            t.getOrganizationId(), e.getMessage());
                }
            }
        } finally {
            // The init loop borrows the startup thread's TenantContext; clear it so the id does
            // not leak into later work on this thread.
            TenantContext.clear();
        }
        log.info("File-storage directory check complete.");
    }

    private void ensureDirectory(String label, Path path) throws IOException {
        if (Files.notExists(path)) {
            log.info("{} path '{}' does not exist, creating it.", label, path);
            Files.createDirectories(path);
        }
    }

    public Path getRoot() {
        Tenant t = requireTenant();
        FileStorage fs = requireFileStorage(t);
        if (fs.getRootPath() == null || fs.getRootPath().isBlank()) {
            throw new IllegalStateException(missing(t, "root-path"));
        }
        return Path.of(fs.getRootPath());
    }

    public Path getTrash() {
        Tenant t = requireTenant();
        FileStorage fs = requireFileStorage(t);
        if (fs.getTrashPath() == null || fs.getTrashPath().isBlank()) {
            throw new IllegalStateException(missing(t, "trash-path"));
        }
        return Path.of(fs.getTrashPath());
    }

    private Tenant requireTenant() {
        String tenantId = TenantContext.getTenantId();
        Tenant t = tenantConfigService.getConfig(tenantId);
        if (t == null) {
            throw new IllegalStateException(
                    "No tenant configuration found for tenant id '" + tenantId + "'.");
        }
        return t;
    }

    private FileStorage requireFileStorage(Tenant t) {
        FileStorage fs = t.getFileStorage();
        if (fs == null) {
            throw new IllegalStateException(
                    "Tenant '" + t.getOrganizationId() + "' (org '" + t.getOrganizationName()
                    + "') has no file-storage configuration. Add a file-storage block "
                    + "(host, root-path, trash-path) to the tenant's Vault entry.");
        }
        return fs;
    }

    private String missing(Tenant t, String key) {
        return "Tenant '" + t.getOrganizationId() + "' (org '" + t.getOrganizationName()
                + "') is missing file-storage." + key + " in its Vault configuration; "
                + "cannot resolve the file storage path.";
    }
}
