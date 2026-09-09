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
 * <p>The API delete path moves the file to the tenant trash folder and stamps {@code deleted_at} on
 * its inode. This janitor, per tenant, reads the trashed inodes and for anything older than
 * {@link FileCleanupProperties#getDeletedFileGrace()} (default 30 days) unlinks its file from the
 * trash and hard-deletes the row (+ its owned child rows) via {@link TrashPurger}. Dry-run (default
 * in the {@code dev} profile) logs and touches nothing.
 *
 * <p>The deletion time used to be recoverable only by parsing a trailing {@code _<epochMillis>} off
 * an external id that delete had rewritten, so a row whose id did not parse was skipped every run
 * and never purged. It is a column now, and the migration that added it backfilled the older rows
 * from exactly that suffix, so nothing here parses anything.
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
        long cutoffMillis = Instant.now().minus(props.getDeletedFileGrace()).toEpochMilli();
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
                total += purgeTenant(tenantId, Path.of(fs.getTrashPath()).normalize(), cutoffMillis);
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

    private int purgeTenant(String tenantId, Path trashDir, long cutoffMillis) {
        int purged = 0;
        for (TrashedNode node : trashPurger.findTrashed()) {
            if (node.deletedAt().toEpochMilli() >= cutoffMillis) {
                continue; // still within the grace window — restorable
            }
            if (props.isDryRun()) {
                log.info("[dry-run] Would purge trashed inode id={} externalId={} (tenant {}).",
                        node.id(), node.externalId(), tenantId);
                purged++;
                continue;
            }
            // A trashed FILE is stored at <trash>/<id>; a trashed FOLDER keeps no bytes there, so
            // deleteIfExists is a harmless no-op for it. Files trashed before deleted_at existed are
            // still filed under their rewritten external id. The containment check stays: it is
            // meaningless for a numeric id, and it is exactly what a legacy id still needs.
            Path target = trashDir.resolve(trashFileName(node)).normalize();
            if (!target.startsWith(trashDir)) {
                log.warn("Trashed path '{}' escapes trash dir '{}'; skipping inode id={} (tenant {}).",
                        target, trashDir, node.id(), tenantId);
                continue;
            }
            try {
                Files.deleteIfExists(target);
                // Disk first, then the row: a crash between the two leaves an orphan row whose file is
                // already gone — the next run re-selects it (deleteIfExists no-ops) and finishes.
                trashPurger.hardDelete(node.id());
                purged++;
                log.info("Purged trashed inode id={} externalId={} (tenant {}).", node.id(), node.externalId(), tenantId);
            } catch (Exception e) {
                log.error("Failed to purge trashed inode id={} (tenant {}): {}", node.id(), tenantId, e.getMessage());
            }
        }
        return purged;
    }

    /**
     * What a trashed node is called under the tenant trash folder: its id, or its rewritten external
     * id if it was trashed before {@code deleted_at} existed and is still filed under the old name.
     */
    static String trashFileName(TrashedNode node) {
        String externalId = node.externalId();
        return (externalId != null && externalId.startsWith("DELETED_"))
                ? externalId
                : String.valueOf(node.id());
    }
}
