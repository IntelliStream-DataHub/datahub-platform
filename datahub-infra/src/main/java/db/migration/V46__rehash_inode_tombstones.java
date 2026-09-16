// SPDX-License-Identifier: AGPL-3.0-or-later
package db.migration;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bring {@code inode.external_id_hash} onto {@link ExternalIds#hash}, which V45 made the one
 * identity function for files.
 *
 * <h2>Why only the tombstones move</h2>
 * A live inode's {@code external_id} was written by the old slug rewrite, so it is already
 * lowercase and {@code hash(lower(x)) == hash(x)}: its stored hash is already what the new code
 * computes. The soft-delete tombstones are the exception. {@code moveNodeToTrash} writes them
 * through {@code markDeleted}, a JPQL {@code UPDATE} that bypasses {@code setExternalId}, so a
 * tombstone keeps its uppercase {@code DELETED_} prefix and was hashed verbatim. Those rows are the
 * only ones whose hash changes, and until they are rewritten {@code POST /files/restore} cannot
 * find them by external id.
 *
 * <p>Every row is recomputed rather than just the deleted ones, and only the differences are
 * written. It costs one pass and it is the only way to catch a row the reasoning above did not
 * predict, rather than assuming it cannot exist.
 *
 * <p>Java rather than SQL because XXH3 is not a Postgres function — the same reason
 * {@code V41__rehash_labels_from_xx64_to_xx3} is Java. Runs per tenant on the ordinary Flyway path.
 *
 * <h2>Collisions</h2>
 * Case-folding can make two live rows target one hash ({@code Val-01} and {@code VAL-01} cannot
 * both exist after this, though the old rewrite made them impossible to create in the first place).
 * Such rows are left alone and logged individually rather than failing the migration: a blocked
 * migration blocks tenant provisioning, and merging two files is a data decision this cannot make
 * for an operator. Tombstones are exempt from the check — V45 took them out of the unique index.
 */
public class V46__rehash_inode_tombstones extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V46__rehash_inode_tombstones.class);

    /** One inode row, only the columns this migration reasons about. */
    private record Row(long id, String externalId, long currentHash, boolean deleted) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Statement read = context.getConnection().createStatement();
             ResultSet rs = read.executeQuery(
                     "SELECT id, external_id, external_id_hash, is_deleted FROM inodes")) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong("id"), rs.getString("external_id"),
                        rs.getLong("external_id_hash"), rs.getBoolean("is_deleted")));
            }
        }
        if (rows.isEmpty()) {
            return;
        }

        // Live rows only: V45's unique index excludes tombstones, so two of those sharing a hash is
        // not a conflict and must not stop the rewrite.
        Map<Long, List<Row>> liveByTarget = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!row.deleted() && row.externalId() != null) {
                liveByTarget.computeIfAbsent(ExternalIds.hash(row.externalId()), k -> new ArrayList<>()).add(row);
            }
        }

        List<Long> blocked = new ArrayList<>();
        liveByTarget.forEach((target, sharing) -> {
            if (sharing.size() > 1) {
                sharing.forEach(row -> blocked.add(row.id()));
                log.error("Files {} have external ids that differ only by case and cannot share hash "
                                + "{}. Leaving them as they are; they will not be findable by external "
                                + "id until one is renamed.",
                        sharing.stream().map(row -> row.id() + "=" + row.externalId()).toList(), target);
            }
        });

        List<Row> stale = rows.stream()
                .filter(row -> row.externalId() != null)
                .filter(row -> !blocked.contains(row.id()))
                .filter(row -> ExternalIds.hash(row.externalId()) != row.currentHash())
                .toList();
        if (stale.isEmpty()) {
            log.info("Inode external-id hashes are already current; nothing to rewrite.");
            return;
        }

        try (PreparedStatement update = context.getConnection()
                .prepareStatement("UPDATE inodes SET external_id_hash = ? WHERE id = ?")) {
            for (Row row : stale) {
                update.setLong(1, ExternalIds.hash(row.externalId()));
                update.setLong(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        long tombstones = stale.stream().filter(Row::deleted).count();
        log.info("Rewrote {} inode external-id hash(es) of {} row(s) ({} tombstone(s)); {} left for "
                + "manual rename.", stale.size(), rows.size(), tombstones, blocked.size());
    }
}
