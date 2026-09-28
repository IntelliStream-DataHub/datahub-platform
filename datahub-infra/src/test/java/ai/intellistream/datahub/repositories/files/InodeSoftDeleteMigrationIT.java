// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.files;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V45 moves soft-delete state out of {@code DELETED_…} tombstones into {@code deleted_at} and
 * {@code trash_name}, restores each trashed row's original external id, drops {@code is_deleted}
 * in favour of {@code deleted_at}, and scopes path and external-id uniqueness to live rows. Rows are seeded exactly as the old delete path wrote them, with Flyway
 * stopped at V44, then the rest of the migrations run over them.
 */
@Tag("integration")
class InodeSoftDeleteMigrationIT {

    private static final long EPOCH = 1783494804120L;
    private static final String FILE_TOMBSTONE = "DELETED_abcd12_report_pdf_" + EPOCH;
    private static final String FOLDER_TOMBSTONE = "DELETED__datahub_folder_docs_" + EPOCH;

    private static Flyway flyway(PGSimpleDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).target(target).load();
    }

    /**
     * A row as the pre-V45 code wrote it: deletion was the {@code is_deleted} flag, and a tombstone's
     * hash was taken over the raw, uppercase string. A null {@code lastUpdated} is left null.
     */
    private static void insertLegacy(Connection c, long id, String externalId, long hash, boolean deleted,
                                     int nodeType, String path, Instant lastUpdated) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO inodes (id, external_id, external_id_hash, is_deleted, name, node_type, path,
                                    path_hash, last_updated)
                VALUES (?, ?, ?, ?, 'n', ?, ?, ?, ?)""")) {
            ps.setLong(1, id);
            ps.setString(2, externalId);
            ps.setLong(3, hash);
            ps.setBoolean(4, deleted);
            ps.setInt(5, nodeType);
            ps.setString(6, path);
            ps.setLong(7, IdGenerator.xxHash(path));
            ps.setTimestamp(8, lastUpdated == null ? null : Timestamp.from(lastUpdated));
            ps.executeUpdate();
        }
    }

    /** A row as the current code writes it: deleted exactly when {@code deletedAt} is non-null. */
    private static void insert(Connection c, long id, String externalId, Instant deletedAt, String path)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO inodes (id, external_id, external_id_hash, deleted_at, name, node_type, path, path_hash)
                VALUES (?, ?, ?, ?, 'n', 0, ?, ?)""")) {
            ps.setLong(1, id);
            ps.setString(2, externalId);
            ps.setLong(3, ExternalIds.hash(externalId));
            ps.setTimestamp(4, deletedAt == null ? null : Timestamp.from(deletedAt));
            ps.setString(5, path);
            ps.setLong(6, IdGenerator.xxHash(path));
            ps.executeUpdate();
        }
    }

    @Test
    void trashedRowsGetTheirOriginalIdBackAndTheirStateInColumns() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.newDatabase("inode_soft_delete_it"));
        ds.setUser(SharedPostgres.username());
        ds.setPassword(SharedPostgres.password());
        // The clone is already fully migrated; start over so Flyway can stop short of V45.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        flyway(ds, "44").migrate();

        Instant lastUpdated = Instant.ofEpochMilli(EPOCH - 1000);
        try (Connection c = ds.getConnection()) {
            // A live file, and a trashed earlier copy of the same external id.
            insertLegacy(c, 1, "report_pdf", ExternalIds.hash("report_pdf"), false, 0, "/report.pdf", lastUpdated);
            insertLegacy(c, 2, FILE_TOMBSTONE, IdGenerator.xxHash(FILE_TOMBSTONE), true, 0, "/report.pdf", lastUpdated);
            insertLegacy(c, 3, FOLDER_TOMBSTONE, IdGenerator.xxHash(FOLDER_TOMBSTONE), true, 1, "/docs", lastUpdated);
            insertLegacy(c, 4, "weird", IdGenerator.xxHash("weird"), true, 0, "/weird", lastUpdated);
            insertLegacy(c, 5, "undated", IdGenerator.xxHash("undated"), true, 0, "/undated", null);
        }

        Instant beforeMigrating = Instant.now();
        flyway(ds, "latest").migrate();

        try (Connection c = ds.getConnection()) {
            assertRow(c, 1, "report_pdf", null, null);
            assertRow(c, 2, "report_pdf", Instant.ofEpochMilli(EPOCH), FILE_TOMBSTONE);
            assertRow(c, 3, "datahub_folder_docs", Instant.ofEpochMilli(EPOCH), null);
            // Unrecognised: keeps its id and is dated from last_updated rather than left undatable.
            assertRow(c, 4, "weird", lastUpdated, "weird");
            // Nothing to date it from: stamped with the migration time, since a null deleted_at
            // would now mean the row is live.
            assertThat(deletedAt(c, 5)).isAfterOrEqualTo(beforeMigrating.minusSeconds(1));

            // Uniqueness now covers live rows only: another trashed copy is fine, a second live one is not.
            insert(c, 6, "report_pdf", Instant.now(), "/report.pdf");
            assertThatThrownBy(() -> insert(c, 7, "REPORT_PDF", null, "/other.pdf"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("inodes_external_id_hash_active_uk");
            assertThatThrownBy(() -> insert(c, 8, "another.pdf", null, "/report.pdf"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("inodes_path_hash_active_uk");
        }
    }

    private static void assertRow(Connection c, long id, String externalId, Instant deletedAt, String trashName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT external_id, external_id_hash, deleted_at, trash_name FROM inodes WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("external_id")).isEqualTo(externalId);
                assertThat(rs.getLong("external_id_hash")).isEqualTo(ExternalIds.hash(externalId));
                Timestamp stored = rs.getTimestamp("deleted_at");
                assertThat(stored == null ? null : stored.toInstant()).isEqualTo(deletedAt);
                assertThat(rs.getString("trash_name")).isEqualTo(trashName);
            }
        }
    }

    private static Instant deletedAt(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT deleted_at FROM inodes WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getTimestamp("deleted_at").toInstant();
            }
        }
    }
}
