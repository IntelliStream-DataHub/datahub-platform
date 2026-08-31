// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.label;

import ai.intellistream.datahub.helpers.text.Labels;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import db.migration.V41__rehash_labels_from_xx64_to_xx3;
import net.openhft.hashing.LongHashFunction;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V37 repairs label hashes written before the algorithm changed.
 *
 * <p>Seeds a row exactly as the pre-2026-07-20 code did — name {@code DATASET}, hash from XXH64 —
 * which is the row shape observed in production (label id 1, hash 455134091631135939) and the reason
 * filtering by DATASET returned nothing while every read showed the label.
 *
 * <p>The migration is invoked directly rather than through {@code Flyway.migrate()}, because Flyway
 * applies it during the schema build, before any row exists to repair, and will not re-run an
 * applied version. Discovery is covered separately: Flyway reports it as {@code 37(JDBC)} from the
 * same {@code classpath:db/migration} location the tenant migrations use.
 */
@Tag("integration")
class LabelRehashMigrationIT {

    /** The hash the old code wrote: XXH64 rather than XXH3. */
    private static long legacyHash(String name) {
        return LongHashFunction.xx().hashChars(name);
    }

    private static PGSimpleDataSource migratedDatabase(String name) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.newDatabase(name));
        ds.setUser(SharedPostgres.username());
        ds.setPassword(SharedPostgres.password());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
        return ds;
    }

    private static void runMigration(Connection connection) throws Exception {
        new V41__rehash_labels_from_xx64_to_xx3().migrate(new Context() {
            @Override public Configuration getConfiguration() { return null; }
            @Override public Connection getConnection() { return connection; }
        });
    }

    private static long hashOf(Connection c, String name) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT hash FROM label WHERE name = '" + name + "'")) {
            assertThat(rs.next()).as("row %s exists", name).isTrue();
            return rs.getLong("hash");
        }
    }

    @Test
    @DisplayName("Flyway discovers the Java migration from the tenant migration location")
    void theMigrationIsDiscovered() {
        PGSimpleDataSource ds = migratedDatabase("label_rehash_discovery_it");

        var applied = Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().info().all();

        assertThat(Arrays.stream(applied).map(i -> i.getVersion().getVersion()).toList())
                .as("a Java migration in package db.migration must be found alongside the SQL ones")
                .contains("37");
    }

    @Test
    @DisplayName("a label hashed with the old algorithm is rewritten to the current one")
    void staleHashesAreRewritten() throws Exception {
        PGSimpleDataSource ds = migratedDatabase("label_rehash_it");

        try (Connection c = ds.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO label (hash, name, color) VALUES ("
                        + legacyHash("DATASET") + ", 'DATASET', '#a1319f')");
                s.executeUpdate("INSERT INTO label (hash, name, color) VALUES ("
                        + Labels.hash("TIMESERIES") + ", 'TIMESERIES', '#ffffff')");
            }
            assertThat(hashOf(c, "DATASET")).isEqualTo(455134091631135939L); // the production value

            runMigration(c);

            assertThat(hashOf(c, "DATASET"))
                    .as("the stale XXH64 hash must become the XXH3 one the application computes")
                    .isEqualTo(Labels.hash("DATASET"));
            assertThat(hashOf(c, "TIMESERIES"))
                    .as("an already-correct row is left exactly as it was")
                    .isEqualTo(Labels.hash("TIMESERIES"));
        }
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void theMigrationIsIdempotent() throws Exception {
        PGSimpleDataSource ds = migratedDatabase("label_rehash_idempotent_it");

        try (Connection c = ds.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO label (hash, name, color) VALUES ("
                        + legacyHash("PUMP") + ", 'PUMP', '#333333')");
            }
            runMigration(c);
            long after = hashOf(c, "PUMP");
            runMigration(c);

            assertThat(hashOf(c, "PUMP")).isEqualTo(after).isEqualTo(Labels.hash("PUMP"));
        }
    }

    @Test
    @DisplayName("names that canonicalise together are left alone rather than failing the migration")
    void collidingNamesAreSkippedNotFatal() throws Exception {
        PGSimpleDataSource ds = migratedDatabase("label_rehash_collision_it");

        // Both canonicalise to SENSOR, so both target one hash. Failing here would block tenant
        // provisioning; merging them is a data decision the migration cannot make.
        try (Connection c = ds.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO label (hash, name, color) VALUES ("
                        + legacyHash("SENSOR") + ", 'SENSOR', '#111111')");
                s.executeUpdate("INSERT INTO label (hash, name, color) VALUES ("
                        + legacyHash("Sensor") + ", 'Sensor', '#222222')");
            }

            runMigration(c);   // must not throw

            assertThat(hashOf(c, "SENSOR")).isEqualTo(legacyHash("SENSOR"));
            assertThat(hashOf(c, "Sensor")).isEqualTo(legacyHash("Sensor"));
        }
    }
}
