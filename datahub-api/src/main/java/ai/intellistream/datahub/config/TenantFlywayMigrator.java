// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantAddedEvent;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantMigrationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provisions each tenant's Postgres schema <em>on demand</em> inside datahub-api, so a hot-loaded
 * organization is migrated before it is served. It never retries on a timer — a tenant whose
 * migration fails is left for datahub-cleanup's scheduled self-heal sweep (which owns the persistent
 * retry with exponential backoff). The actual migration mechanics live in the shared
 * {@link TenantMigrationService} in datahub-infra.
 *
 * <p>Three provisioning moments, all fault-isolated (one broken tenant never aborts startup or a
 * request for a healthy one):
 * <ul>
 *   <li><b>boot</b> — {@link #migrateAllTenants()} migrates every cached tenant at startup;</li>
 *   <li><b>hot-load</b> — {@link #onTenantAdded} migrates an org that {@code TenantConfigService}
 *       discovers in Vault at runtime (a first request for an unknown org triggers this via the
 *       cache-miss refresh that fires {@link TenantAddedEvent});</li>
 *   <li><b>first touch</b> — {@link #ensureProvisioned} runs on the request path (see
 *       {@code TenantProvisioningFilter}) for an org not yet confirmed in this JVM.</li>
 * </ul>
 *
 * <p><b>How the api knows a migration actually succeeded:</b> only a {@link TenantMigrationService#migrate}
 * call that returns without throwing counts — that return means Flyway confirmed the schema against
 * the tenant's own {@code flyway_schema_history}. Confirmed org-ids are cached in {@link #confirmed}
 * so the check is paid once per org per JVM, not per request. The cache is populated <em>only</em>
 * by a real success, and because a not-yet-confirmed org is re-verified against the tenant DB (not a
 * stale in-memory flag), this correctly picks up a migration that datahub-cleanup completed
 * out-of-band: the next request re-runs the idempotent migrate, sees the DB already at version, and
 * confirms.
 *
 * <p>Enabled per environment via {@code datahub.flyway.per-tenant-migrate: true}.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "datahub.flyway.per-tenant-migrate", havingValue = "true")
@DependsOn("tenantConfigService")
public class TenantFlywayMigrator {

    private final TenantConfigService tenantConfigService;
    private final TenantMigrationService migrationService;

    // Org-ids confirmed migrated in THIS JVM (a migrate() that returned success). Never a source of
    // truth on its own — it only caches successes so the DB round-trip is paid once per org.
    private final Set<String> confirmed = ConcurrentHashMap.newKeySet();

    public TenantFlywayMigrator(TenantConfigService tenantConfigService,
                                TenantMigrationService migrationService) {
        this.tenantConfigService = tenantConfigService;
        this.migrationService = migrationService;
    }

    @PostConstruct
    public void migrateAllTenants() {
        var tenants = tenantConfigService.cachedTenants.values();
        if (tenants.isEmpty()) {
            // Zero tenants is a *global* problem (Vault unreachable, wrong path/credentials),
            // not a per-tenant one — that one is still worth failing fast on.
            throw new IllegalStateException(
                    "TenantFlywayMigrator found no tenants to migrate. " +
                            "Refusing to start — verify Vault is reachable and credentials are correct.");
        }

        log.info("Provisioning Flyway schema across {} tenant(s)...", tenants.size());
        int ok = 0;
        for (Tenant tenant : tenants) {
            if (migrateSafely(tenant)) {
                ok++;
            }
        }
        int failed = tenants.size() - ok;
        log.info("Startup provisioning complete: {}/{} tenant(s) migrated.{}", ok, tenants.size(),
                failed == 0 ? "" : " " + failed + " unhealthy — datahub-cleanup will retry them.");
    }

    @EventListener
    public void onTenantAdded(TenantAddedEvent event) {
        Tenant tenant = event.tenant();
        log.info("Provisioning Flyway schema for newly cached tenant {}.", tenant.getOrganizationId());
        // Must not propagate: this runs inside TenantConfigService.refreshCache(), where an
        // exception would abort the rest of the refresh (other new tenants, removal cleanup).
        migrateSafely(tenant);
    }

    /**
     * Request-path gate: ensures {@code tenantId}'s schema is migrated before the request is served.
     * Returns immediately for an org already confirmed in this JVM; otherwise runs the idempotent
     * migration once and confirms on success.
     *
     * @return {@code true} if the tenant is provisioned and safe to serve; {@code false} if its
     * migration is still failing (caller should refuse the request — datahub-cleanup will heal it).
     * An unknown tenant returns {@code true}: it is not a provisioning problem, and on the request
     * path {@code TenantProvisioningFilter} has already refused it with a 403 before calling this.
     */
    public boolean ensureProvisioned(String tenantId) {
        if (tenantId == null || confirmed.contains(tenantId)) {
            return true;
        }
        Tenant tenant = tenantConfigService.getConfig(tenantId);
        if (tenant == null) {
            // Not a provisioning problem — the filter has already refused it, and off the
            // request path the routing datasource raises UnknownTenantException.
            return true;
        }
        return migrateSafely(tenant);
    }

    /**
     * Migrates one tenant without ever throwing: records success in {@link #confirmed}, logs a
     * failure and returns {@code false} instead of aborting startup or the caller's loop.
     */
    private boolean migrateSafely(Tenant tenant) {
        String orgId = tenant.getOrganizationId();
        try {
            migrationService.migrate(tenant);
            confirmed.add(orgId);
            return true;
        } catch (Exception e) {
            confirmed.remove(orgId);
            log.error("Flyway migration failed for tenant {} ({}) — application keeps running; this "
                            + "tenant is UNHEALTHY. datahub-cleanup will retry it; fix its config (e.g. the "
                            + "Postgres 'uri' in Vault) and it recovers automatically. Cause: {}",
                    tenant.getOrganizationName(), orgId, e.getMessage(), e);
            return false;
        }
    }
}
