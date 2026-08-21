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
 * <p><b>Zero-schema.</b> The API delete path already moves a deleted file to the tenant trash folder,
 * marks its inode {@code is_deleted=true}, and renames its {@code external_id} to
 * {@code DELETED_<...>_<epochMillis>} (see {@code FileSystemService.moveNodeToTrash}) — so the
 * deletion time is already on the row, no new column needed. This janitor, per tenant, reads the
 * trashed inodes, recovers the {@code epochMillis} from {@code external_id}, and for anything older
 * than {@link FileCleanupProperties#getDeletedFileGrace()} (default 30 days): unlinks
 * {@code <trash>/<external_id>} and hard-deletes the row (+ its owned child rows) via
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
            Long deletedAtMillis = deletionEpochMillis(node.externalId());
            if (deletedAtMillis == null) {
                log.warn("Trashed inode id={} has an unparseable external_id '{}'; leaving it (tenant {}).",
                        node.id(), node.externalId(), tenantId);
                continue;
            }
            if (deletedAtMillis >= cutoffMillis) {
                continue; // still within the grace window — restorable
            }
            if (props.isDryRun()) {
                log.info("[dry-run] Would purge trashed inode id={} externalId={} (tenant {}).",
                        node.id(), node.externalId(), tenantId);
                purged++;
                continue;
            }
            // A trashed FILE is stored at <trash>/<external_id>; a trashed FOLDER keeps no bytes there,
            // so deleteIfExists is a harmless no-op for it. Confine the resolved path to the trash dir
            // so a crafted external_id can never unlink something outside it.
            Path target = trashDir.resolve(node.externalId()).normalize();
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
     * Recover the deletion instant a trashed inode carries as the trailing {@code _<epochMillis>}
     * segment of its {@code DELETED_..._<epochMillis>} external id. Returns null if it can't be parsed
     * (leave the node alone rather than guess its age).
     */
    static Long deletionEpochMillis(String externalId) {
        if (externalId == null) {
            return null;
        }
        int idx = externalId.lastIndexOf('_');
        if (idx < 0 || idx == externalId.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(externalId.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
