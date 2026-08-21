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
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Removes file folders belonging to tenants that no longer exist.
 *
 * <p>Two phases, both safe-by-construction (they only ever touch directories that are siblings of
 * a <em>current</em> tenant's storage path, so an empty/failed tenant load deletes nothing):
 * <ol>
 *   <li><b>Quarantine</b> — under each parent of a live tenant's root/trash path, any subdirectory
 *       that is not itself a live tenant root <em>or</em> trash path is moved into a
 *       {@code <parent>/<quarantineDirName>/<name>__quarantined__<epochMillis>} holding dir.</li>
 *   <li><b>Purge</b> — quarantined folders older than {@link
 *       FileCleanupProperties#getQuarantineGrace()} are permanently deleted.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanTenantFolderCleanupTask {

    private static final String SEP = "__quarantined__";

    private final TenantConfigService tenantConfigService;
    private final FileCleanupProperties props;

    // Default: every 15 minutes. Override with datahub.cleanup.file.orphan-cron.
    @Scheduled(cron = "${datahub.cleanup.file.orphan-cron:0 */15 * * * *}")
    public void cleanOrphanTenantFolders() {
        Set<Path> liveRoots = new HashSet<>();
        Set<Path> liveTrash = new HashSet<>();
        Set<Path> rootParents = new HashSet<>();
        Set<Path> trashParents = new HashSet<>();

        for (Tenant t : tenantConfigService.cachedTenants.values()) {
            FileStorage fs = t.getFileStorage();
            if (fs == null) {
                continue;
            }
            addPath(fs.getRootPath(), liveRoots, rootParents);
            addPath(fs.getTrashPath(), liveTrash, trashParents);
        }

        if (liveRoots.isEmpty() && liveTrash.isEmpty()) {
            // Without any known tenant paths we have nothing to compare against — refuse to scan
            // so a failed tenant load can never be interpreted as "every folder is an orphan".
            log.warn("No tenant file-storage paths loaded; skipping orphan cleanup.");
            return;
        }

        // A folder is an orphan only if it is neither a live root NOR a live trash path. Root and
        // trash can share a parent (e.g. <tenant>/files + <tenant>/trash), so each parent must be
        // compared against BOTH sets at once — comparing the root parent against liveRoots alone
        // would quarantine the live trash dir sitting beside it, and vice-versa.
        Set<Path> allLive = new HashSet<>(liveRoots);
        allLive.addAll(liveTrash);
        Set<Path> allParents = new HashSet<>(rootParents);
        allParents.addAll(trashParents);

        // Never scan a parent that is itself a live path or sits inside one: with a nested layout
        // (e.g. trash configured INSIDE the root), the trash's "parent" is the tenant's own live
        // root, and scanning it would quarantine every real data folder in it.
        allParents.removeIf(parent -> {
            boolean insideLive = allLive.stream().anyMatch(parent::startsWith);
            if (insideLive) {
                log.warn("Skipping orphan scan under '{}': it is (or is inside) a live tenant path — "
                        + "nested root/trash layouts are not scanned.", parent);
            }
            return insideLive;
        });

        quarantineOrphans(allParents, allLive);

        Instant cutoff = Instant.now().minus(props.getQuarantineGrace());
        purgeExpiredQuarantine(allParents, cutoff);
    }

    private void addPath(String raw, Set<Path> live, Set<Path> parents) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path p = Path.of(raw).normalize();
        live.add(p);
        Path parent = p.getParent();
        if (parent != null) {
            parents.add(parent);
        }
    }

    private void quarantineOrphans(Set<Path> parents, Set<Path> livePaths) {
        for (Path parent : parents) {
            if (Files.notExists(parent)) {
                continue;
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(parent, Files::isDirectory)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (name.equals(props.getQuarantineDirName())) {
                        continue; // never quarantine the quarantine dir itself
                    }
                    if (livePaths.contains(child.normalize())) {
                        continue; // belongs to a current tenant
                    }
                    quarantine(parent, child);
                }
            } catch (IOException e) {
                log.warn("Could not scan storage parent '{}': {}", parent, e.getMessage());
            }
        }
    }

    private void quarantine(Path parent, Path orphan) {
        if (props.isDryRun()) {
            log.warn("[dry-run] Would quarantine orphaned tenant folder '{}'", orphan);
            return;
        }
        try {
            Path quarantineDir = parent.resolve(props.getQuarantineDirName());
            Files.createDirectories(quarantineDir);
            String quarantinedName = orphan.getFileName().toString() + SEP + Instant.now().toEpochMilli();
            Path target = quarantineDir.resolve(quarantinedName);
            Files.move(orphan, target);
            log.warn("Quarantined orphaned tenant folder '{}' -> '{}'", orphan, target);
        } catch (IOException e) {
            log.error("Failed to quarantine orphan folder '{}': {}", orphan, e.getMessage());
        }
    }

    private void purgeExpiredQuarantine(Set<Path> parents, Instant cutoff) {
        for (Path parent : parents) {
            Path quarantineDir = parent.resolve(props.getQuarantineDirName());
            if (Files.notExists(quarantineDir)) {
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(quarantineDir)) {
                for (Path entry : entries) {
                    Long quarantinedAt = quarantineTimestamp(entry.getFileName().toString());
                    if (quarantinedAt == null) {
                        log.warn("Quarantine entry '{}' has no recognizable timestamp; leaving it.", entry);
                        continue;
                    }
                    if (Instant.ofEpochMilli(quarantinedAt).isBefore(cutoff)) {
                        if (props.isDryRun()) {
                            log.warn("[dry-run] Would delete expired quarantined folder '{}'.", entry);
                        } else {
                            FileCleanupOps.deleteRecursively(entry);
                            log.warn("Deleted expired quarantined folder '{}'.", entry);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read quarantine dir '{}': {}", quarantineDir, e.getMessage());
            }
        }
    }

    private Long quarantineTimestamp(String name) {
        int idx = name.lastIndexOf(SEP);
        if (idx < 0) {
            return null;
        }
        try {
            return Long.parseLong(name.substring(idx + SEP.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
