// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Service;

import java.sql.Driver;
import java.util.Properties;

/**
 * Stateless engine that runs Flyway migrations against a single tenant's Postgres. Owns only the
 * mechanics — build the tenant DataSource, run {@code classpath:db/migration} — and nothing about
 * <em>when</em> or <em>how often</em> to migrate.
 *
 * <p>It lives in datahub-infra, alongside the migration scripts, so both callers reuse the exact
 * same engine and script set:
 * <ul>
 *   <li><b>datahub-api</b> provisions a tenant on demand (boot, hot-load {@code TenantAddedEvent},
 *       and first request for an unconfirmed org) — see {@code TenantFlywayMigrator};</li>
 *   <li><b>datahub-cleanup</b> runs the scheduled self-heal sweep that retries tenants whose
 *       migration previously failed — see {@code TenantMigrationSweep}.</li>
 * </ul>
 *
 * <p>Migrations are idempotent and Flyway takes a lock on the tenant's schema-history table, so it
 * is safe for the api and the cleanup sweep to call this concurrently for the same tenant: whoever
 * arrives second reads an up-to-date {@code flyway_schema_history} and returns a fast no-op. That
 * table — in the tenant's own Postgres — is the single source of truth for "is this tenant
 * migrated"; a successful return here means Flyway confirmed the schema is at the expected version.
 */
@Service
@Slf4j
public class TenantMigrationService {

    /**
     * Migrates one tenant to the latest schema version. Throws on any failure (missing config,
     * unreachable DB, failed migration) — callers decide whether to isolate, retry, or fail fast.
     * A normal return means Flyway confirmed the schema is at the expected version (a no-op when
     * already up to date). Returns {@code void} so callers need no Flyway type on their classpath.
     */
    public void migrate(Tenant tenant) {
        PostgresTenant pg = tenant.getPostgresTenant();
        if (pg == null) {
            throw new IllegalStateException(
                    "Tenant " + tenant.getOrganizationName() + " has no postgres config.");
        }

        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(loadPostgresDriver());
        ds.setUrl(pg.getJdbcUrl());
        ds.setUsername(pg.getUsername());
        String password = pg.getPassword();
        ds.setPassword(password != null ? password : "");
        Properties props = new Properties();
        props.setProperty("connectTimeout", "5");
        props.setProperty("socketTimeout", "30");
        ds.setConnectionProperties(props);

        MigrateResult result = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
        log.info("Tenant {}: migrated to schema version {} ({} migration(s) applied).",
                tenant.getOrganizationId(), result.targetSchemaVersion, result.migrationsExecuted);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Driver> loadPostgresDriver() {
        try {
            // Resolved at runtime — postgresql is an implementation dep in datahub-infra.
            return (Class<? extends Driver>) Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("org.postgresql.Driver not on classpath", e);
        }
    }
}
