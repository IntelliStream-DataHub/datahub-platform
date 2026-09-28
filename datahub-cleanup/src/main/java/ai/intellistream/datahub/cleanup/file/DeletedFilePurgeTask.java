// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.file;

import ai.intellistream.datahub.cleanup.file.TrashPurger.TrashedNode;
import ai.intellistream.datahub.tenant.FileStorage;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Permanently deletes soft-deleted (trashed) files once their retention window has elapsed.
 *
 * <p>The API delete path moves a deleted file to the tenant trash folder as {@code trash_name}, marks
 * its inode deleted by stamping {@code deleted_at} (see {@code FileSystemService.delete}).
 * This janitor, per tenant, reads the trashed inodes and for anything deleted longer ago than
 * {@link FileCleanupProperties#getDeletedFileGrace()} (default 30 days): unlinks
 * {@code <trash>/<trash_name>} and hard-deletes the row (+ its owned child rows) via
 * {@link TrashPurger}. Dry-run (default in the {@code dev} profile) logs and touches nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeletedFilePurgeTask {

    private final TenantConfigService tenantConfigService;
    private final TrashPurger trashPurger;
    private final FileCleanupProperties props;

    // Default: daily at 02:30. Override with datahub.cleanup.file.deleted-file-cron.
    @Scheduled(cron = "${datahub.cleanup.file.deleted-file-cron:0 30 2 * * *}")
    public void purgeExpiredTrash() {
        Instant cutoff = Instant.now().minus(props.getDeletedFileGrace());
        Map<String, Tenant> tenants = tenantConfigService.cachedTenants;
        log.info("Deleted-file purge starting ({}, grace={}) over {} tenant(s).",
                props.isDryRun() ? "dry-run" : "deleting", props.getDeletedFileGrace(), tenants.size());

        int total = 0;
        for (Tenant tenant : tenants.values()) {
            String tenantId = tenant.getOrganizationId();
            if (tenantId == null || tenantId.isBlank()) {
                continue;
            }
            FileStorage fs = tenant.getFileStorage();
            if (fs == null || fs.getTrashPath() == null || fs.getTrashPath().isBlank()) {
                log.warn("Tenant '{}' has no file-storage trash-path; skipping deleted-file purge.", tenantId);
                continue;
            }
            // Route the repository/EntityManager to this tenant's Postgres for the purge.
            String previous = TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(tenantId);
                total += purgeTenant(tenantId, Path.of(fs.getTrashPath()).normalize(), cutoff);
            } catch (Exception e) {
                log.error("Deleted-file purge failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setTenantId(previous);
                }
            }
        }
        log.info("Deleted-file purge finished. {} node(s) {}.",
                total, props.isDryRun() ? "would be purged (dry-run)" : "purged");
    }

    private int purgeTenant(String tenantId, Path trashDir, Instant cutoff) {
        int purged = 0;
        for (TrashedNode node : trashPurger.findTrashed()) {
            if (!node.deletedAt().isBefore(cutoff)) {
                continue; // still within the grace window — restorable
            }
            if (props.isDryRun()) {
                log.info("[dry-run] Would purge trashed inode id={} (tenant {}).", node.id(), tenantId);
                purged++;
                continue;
            }
            try {
                // A trashed FILE is stored at <trash>/<trash_name>; a trashed FOLDER keeps nothing there.
                // Confine the resolved path to the trash dir so a bad trash_name can never unlink
                // something outside it.
                if (node.trashName() != null) {
                    Path target = trashDir.resolve(node.trashName()).normalize();
                    if (!target.startsWith(trashDir)) {
                        log.warn("Trashed path '{}' escapes trash dir '{}'; skipping inode id={} (tenant {}).",
                                target, trashDir, node.id(), tenantId);
                        continue;
                    }
                    Files.deleteIfExists(target);
                }
                // Disk first, then the row: a crash between the two leaves an orphan row whose file is
                // already gone — the next run re-selects it (deleteIfExists no-ops) and finishes.
                trashPurger.hardDelete(node.id());
                purged++;
                log.info("Purged trashed inode id={} (tenant {}).", node.id(), tenantId);
            } catch (Exception e) {
                log.error("Failed to purge trashed inode id={} (tenant {}): {}", node.id(), tenantId, e.getMessage());
            }
        }
        return purged;
    }
}
