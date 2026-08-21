// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.file;

import ai.intellistream.datahub.tenant.FileStorage;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * Deletes stale upload temp files. Uploads stream to a single staging directory at the tenant
 * root ({@code <root>/<tempDirName>/<uuid>.part}) and are atomically renamed into place on
 * success; a process that dies mid-upload leaves the temp entry behind. This scans that one
 * directory per tenant (no tree walk) and removes entries older than
 * {@link FileCleanupProperties#getStaleUploadAge()}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TempUploadCleanupTask {

    private final TenantConfigService tenantConfigService;
    private final FileCleanupProperties props;

    // Default: hourly, on the hour. Override with datahub.cleanup.file.temp-upload-cron.
    @Scheduled(cron = "${datahub.cleanup.file.temp-upload-cron:0 0 * * * *}")
    public void cleanStaleTempUploads() {
        Instant cutoff = Instant.now().minus(props.getStaleUploadAge());
        log.info("Temp-upload cleanup: {} '{}' entries older than {} ({})",
                props.isDryRun() ? "[dry-run] would remove" : "removing",
                props.getTempDirName(), props.getStaleUploadAge(), cutoff);

        int tenants = 0;
        for (Tenant t : tenantConfigService.cachedTenants.values()) {
            FileStorage fs = t.getFileStorage();
            if (fs == null || fs.getRootPath() == null || fs.getRootPath().isBlank()) {
                log.warn("Tenant '{}' has no file-storage root-path; skipping temp cleanup.",
                        t.getOrganizationId());
                continue;
            }
            Path root = Path.of(fs.getRootPath());
            if (Files.notExists(root)) {
                continue;
            }
            cleanTenant(t.getOrganizationId(), root, cutoff);
            tenants++;
        }
        log.info("Temp-upload cleanup finished ({} tenant roots scanned).", tenants);
    }

    private void cleanTenant(String tenantId, Path root, Instant cutoff) {
        // Uploads stage in one dir at the tenant root, so there is exactly one dir to scan.
        Path tmpDir = root.resolve(props.getTempDirName());
        if (Files.isDirectory(tmpDir)) {
            purge(tenantId, tmpDir, cutoff);
        }
    }

    private void purge(String tenantId, Path tmpDir, Instant cutoff) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tmpDir)) {
            for (Path entry : entries) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        if (props.isDryRun()) {
                            log.info("[dry-run] Would delete stale upload temp '{}' (tenant '{}').", entry, tenantId);
                        } else {
                            FileCleanupOps.deleteRecursively(entry);
                            log.info("Deleted stale upload temp '{}' (tenant '{}').", entry, tenantId);
                        }
                    }
                } catch (IOException e) {
                    log.warn("Could not inspect/delete temp entry '{}': {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not read temp dir '{}': {}", tmpDir, e.getMessage());
        }
    }
}
