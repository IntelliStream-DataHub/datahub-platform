// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code flow} schema (V46) as a tenant gets it: an empty database migrated by
 * {@link TenantMigrationService}, the engine datahub-api and datahub-cleanup provision tenants with.
 *
 * <p>Checks what the run protocol leans on rather than every column: the claim's and reaper's
 * partial indexes, the absence of an index on the lease (heartbeats must stay HOT updates), that a
 * flow version cannot be changed, that a flow and its first version go in together, and that a
 * deployment, which lives on the flow row, carries its trigger and identity.
 */
@Tag("integration")
class FlowSchemaMigrationIT {

    private static String url;

    @BeforeAll
    static void migrate() {
        url = SharedPostgres.newEmptyDatabase("flow_schema_it");
        Tenant tenant = new Tenant();
        tenant.setOrganizationId("flow-schema-it");
        tenant.setOrganizationName("flow-schema-it");
        PostgresTenant pg = new PostgresTenant();
        pg.setJdbcUrl(url);
        pg.setUsername(SharedPostgres.username());
        pg.setPassword(SharedPostgres.password());
        tenant.setPostgresTenant(pg);

        TenantMigrationService migrations = new TenantMigrationService();
        migrations.migrate(tenant);
        // A second run is what every later boot does: a no-op, not a failure.
        migrations.migrate(tenant);
    }

    @Test
    void everyTableIsInTheFlowSchema() throws SQLException {
        assertThat(strings("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = 'flow' ORDER BY table_name"))
                .containsExactly("flow", "flow_version", "run");
        assertThat(strings("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name IN ('flow', 'flow_version', 'run')"))
                .isEmpty();
    }

    @Test
    void theClaimAndTheReaperHavePartialIndexesAndTheLeaseHasNone() throws SQLException {
        List<String> runIndexes = strings("SELECT indexdef FROM pg_indexes"
                + " WHERE schemaname = 'flow' AND tablename = 'run'");

        assertThat(runIndexes).anySatisfy(def -> assertThat(def)
                .contains("run_pending_idx").contains("(window_start, created_at)")
                .contains("WHERE (status = 'PENDING'::text)"));
        assertThat(runIndexes).anySatisfy(def -> assertThat(def)
                .contains("run_running_idx").contains("WHERE (status = 'RUNNING'::text)"));
        assertThat(runIndexes).noneMatch(def -> def.contains("lease_expires_at"));
    }

    @Test
    void aFlowVersionCannotBeChanged() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            insertFlow(s, "immutable_flow");
            c.commit();

            assertThatThrownBy(() -> s.executeUpdate("UPDATE flow.flow_version"
                    + " SET definition = '{\"changed\":true}' WHERE version = 1"))
                    .hasMessageContaining("immutable");
            c.rollback();
        }
    }

    @Test
    void aFlowNeedsItsCurrentVersionByTheEndOfTheTransaction() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("INSERT INTO flow.flow (external_id, data_set_id, data_set_external_id,"
                    + " name, current_version, created_by, updated_by)"
                    + " VALUES ('versionless', 1, 'ds', 'n', 1, 'u', 'u')");

            assertThatThrownBy(c::commit).hasMessageContaining("flow_current_version_fk");
        }
    }

    @Test
    void aDeployedFlowHasATriggerAndAnIdentity() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            insertFlow(s, "deployed_flow");
            c.commit();

            assertThatThrownBy(() -> s.executeUpdate("UPDATE flow.flow SET deployed_version = 1"
                    + " WHERE external_id = 'deployed_flow'"))
                    .hasMessageContaining("check constraint");
            c.rollback();

            s.executeUpdate("UPDATE flow.flow SET deployed_version = 1, enabled = true,"
                    + " trigger = '{\"type\":\"manual\"}', identity_kind = 'deployer',"
                    + " identity_subject = 'sub-1' WHERE external_id = 'deployed_flow'");
            c.commit();
        }
        assertThat(strings("SELECT indexdef FROM pg_indexes"
                + " WHERE schemaname = 'flow' AND indexname = 'flow_due_idx'"))
                .singleElement().asString().contains("WHERE (enabled AND (next_due_at IS NOT NULL))");
    }

    @Test
    void aRunningRunHasALeaseAndAPreviewMayCarryItsOwnDefinition() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO flow.run (id, definition, status, max_attempts,"
                    + " memory_bytes, run_timeout, preview, trigger)"
                    + " VALUES ('" + UUID.randomUUID() + "', '{}', 'PENDING', 1, 1, '1 minute',"
                    + " true, 'preview')");

            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO flow.run (id, definition, status,"
                    + " max_attempts, memory_bytes, run_timeout, preview, trigger)"
                    + " VALUES ('" + UUID.randomUUID() + "', '{}', 'RUNNING', 1, 1, '1 minute',"
                    + " true, 'preview')"))
                    .hasMessageContaining("run_running_has_lease");
        }
    }

    private static void insertFlow(Statement s, String externalId) throws SQLException {
        try (ResultSet rs = s.executeQuery("INSERT INTO flow.flow (external_id, data_set_id,"
                + " data_set_external_id, name, current_version, created_by, updated_by)"
                + " VALUES ('" + externalId + "', 1, 'ds', 'n', 1, 'u', 'u') RETURNING id")) {
            rs.next();
            s.executeUpdate("INSERT INTO flow.flow_version (flow_id, version, definition, created_by)"
                    + " VALUES (" + rs.getLong(1) + ", 1, '{}', 'u')");
        }
    }

    private static List<String> strings(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(url, SharedPostgres.username(), SharedPostgres.password());
    }
}
