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
import java.sql.Timestamp;
import java.sql.Types;

/**
 * Move file soft-delete state out of the external id and into columns of its own.
 *
 * <p>A deleted inode used to have its {@code external_id} rewritten to
 * {@code DELETED_<checksumHex>_<originalId>_<epochMillis>} (folders: {@code DELETED__<originalId>_<epochMillis>}),
 * only to free the value under the table-wide unique constraint on {@code external_id_hash}. That
 * string then doubled as the deletion time and as the file's name in the trash folder, and restore
 * parsed the original id back out of it. The trashed row's hash was also taken over the uppercase
 * string, which no lookup through {@link ExternalIds#hash} could ever reproduce.
 *
 * <p>After this migration a deleted inode keeps its external id, {@code deleted_at} holds when it was
 * deleted, and {@code trash_name} names its file under the trash folder. {@code deleted_at} replaces
 * {@code is_deleted} rather than sitting beside it: a row is deleted exactly when it is non-null, so
 * there is no second flag to drift out of step with it. Uniqueness of path and external id applies
 * to live rows only, as V19 already did for {@code path_hash}.
 *
 * <p>Java rather than SQL because the hash is XXH3, which Postgres cannot compute. Files already in
 * the trash keep their tombstone as {@code trash_name}: the migration runs per tenant schema and does
 * not know where that tenant's trash folder is, so it leaves the disk alone.
 */
public class V45__inode_soft_delete_columns extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V45__inode_soft_delete_columns.class);

    private static final String PREFIX = "DELETED_";
    private static final int FILE = 0;

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement ddl = context.getConnection().createStatement()) {
            ddl.execute("ALTER TABLE inodes ADD COLUMN deleted_at timestamptz NULL");
            ddl.execute("ALTER TABLE inodes ADD COLUMN trash_name varchar(256) NULL");
            // Dropped before the rewrite: several trashed rows, and a live one, may share an original id.
            ddl.execute("ALTER TABLE inodes DROP CONSTRAINT inode_external_id_hash_key");
        }

        int rewritten = 0;
        int unparseable = 0;
        try (Statement read = context.getConnection().createStatement();
             ResultSet rs = read.executeQuery(
                     "SELECT id, external_id, node_type, last_updated FROM inodes WHERE is_deleted = true");
             PreparedStatement update = context.getConnection().prepareStatement(
                     "UPDATE inodes SET external_id = ?, external_id_hash = ?, deleted_at = ?, trash_name = ? "
                             + "WHERE id = ?")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String tombstone = rs.getString("external_id");
                boolean isFile = rs.getInt("node_type") == FILE;
                Long epoch = epochMillis(tombstone);
                String original = originalExternalId(tombstone);
                if (epoch == null || original == null) {
                    unparseable++;
                    log.warn("Trashed inode {} has an unrecognised external id '{}'; keeping it and "
                            + "dating the deletion from last_updated, or now.", id, tombstone);
                }
                String externalId = original != null ? original : tombstone;
                Timestamp deletedAt = epoch != null ? new Timestamp(epoch) : rs.getTimestamp("last_updated");
                // Every deleted row must get a deleted_at, or dropping is_deleted brings it back to life.
                if (deletedAt == null) {
                    deletedAt = new Timestamp(System.currentTimeMillis());
                }

                update.setString(1, externalId);
                update.setLong(2, ExternalIds.hash(externalId));
                update.setTimestamp(3, deletedAt);
                // The tombstone is the file's name in the trash folder. Folders keep nothing there.
                if (isFile) {
                    update.setString(4, tombstone);
                } else {
                    update.setNull(4, Types.VARCHAR);
                }
                update.setLong(5, id);
                update.addBatch();
                rewritten++;
            }
            update.executeBatch();
        }

        try (Statement ddl = context.getConnection().createStatement()) {
            ddl.execute("DROP INDEX inodes_path_hash_active_uk");
            ddl.execute("DROP INDEX inodes_is_deleted_idx");
            ddl.execute("ALTER TABLE inodes DROP COLUMN is_deleted");
            // Same name as V19's, which the API maps to a 409 on the path field.
            ddl.execute("CREATE UNIQUE INDEX inodes_path_hash_active_uk "
                    + "ON inodes (path_hash) WHERE deleted_at IS NULL");
            ddl.execute("CREATE UNIQUE INDEX inodes_external_id_hash_active_uk "
                    + "ON inodes (external_id_hash) WHERE deleted_at IS NULL");
            // The trash listing and the purge read deleted rows only, a small slice of the table.
            ddl.execute("CREATE INDEX inodes_deleted_at_idx ON inodes (deleted_at) WHERE deleted_at IS NOT NULL");
        }
        log.info("Moved soft-delete state of {} trashed inode(s) into columns; {} had no recognisable tombstone.",
                rewritten, unparseable);
    }

    /** The trailing {@code _<epochMillis>} of a tombstone, or null. */
    static Long epochMillis(String tombstone) {
        if (tombstone == null || !tombstone.startsWith(PREFIX)) {
            return null;
        }
        int last = tombstone.lastIndexOf('_');
        try {
            return Long.parseLong(tombstone.substring(last + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The original external id: everything between the first {@code _} after the prefix and the
     * trailing epoch. That first segment is the checksum hex for a file and empty for a folder, and
     * neither contains an underscore, while the original id may.
     */
    static String originalExternalId(String tombstone) {
        if (tombstone == null || !tombstone.startsWith(PREFIX)) {
            return null;
        }
        String rest = tombstone.substring(PREFIX.length());
        int first = rest.indexOf('_');
        int last = rest.lastIndexOf('_');
        if (first < 0 || last <= first + 1) {
            return null;
        }
        return rest.substring(first + 1, last);
    }
}
