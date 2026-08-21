// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.migration;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantAddedEvent;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Owns the <em>persistent</em> retry of tenant Flyway migrations, moved off datahub-api so its
 * request path is never bogged down by a forever-polling loop. datahub-api provisions a tenant
 * on demand and gives up after one attempt; this sweep — running in the single-instance,
 * already-scheduled cleanup app — keeps retrying a broken tenant until an operator's Vault fix lands
 * and it self-heals.
 *
 * <p>Three entry points, all delegating to the shared {@link TenantMigrationService}:
 * <ul>
 *   <li><b>boot pass</b> ({@link #migrateAllAtStartup}) — attempts every tenant once when the app is
 *       ready, seeding the unhealthy set with any that fail;</li>
 *   <li><b>hot-load</b> ({@link #onTenantAdded}) — attempts a tenant newly discovered in Vault;</li>
 *   <li><b>self-heal sweep</b> ({@link #sweep}) — re-reads Vault and retries the unhealthy set on a
 *       schedule, each tenant respecting its own exponential backoff.</li>
 * </ul>
 *
 * <p>Safe alongside datahub-api migrating the same tenant: Flyway locks the schema-history table and
 * migrations are idempotent, so a concurrent attempt is a fast no-op.
 *
 * <p>Run as a SINGLE instance (the cleanup app already is) — one retrier is enough and avoids
 * redundant Vault reads.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantMigrationSweep {

    private final TenantConfigService tenantConfigService;
    private final TenantMigrationService migrationService;
    private final MigrationSweepProperties props;

    /** Org-id → current backoff state for tenants whose latest migration attempt failed. */
    private final Map<String, Unhealthy> unhealthy = new ConcurrentHashMap<>();

    /** Backoff state: how many consecutive failures, and the earliest instant to retry next. */
    record Unhealthy(int failures, Instant nextRetryAt) {}

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAllAtStartup() {
        if (!props.isEnabled()) {
            log.info("Tenant migration sweep disabled; skipping startup provisioning.");
            return;
        }
        var tenants = tenantConfigService.cachedTenants.values();
        log.info("Migration boot pass across {} tenant(s)...", tenants.size());
        int ok = 0;
        for (Tenant tenant : tenants) {
            if (tryMigrate(tenant)) ok++;
        }
        log.info("Migration boot pass complete: {}/{} healthy.{}", ok, tenants.size(),
                unhealthy.isEmpty() ? "" : " Unhealthy, will retry: " + unhealthy.keySet());
    }

    @EventListener
    public void onTenantAdded(TenantAddedEvent event) {
        if (!props.isEnabled()) return;
        Tenant tenant = event.tenant();
        log.info("Provisioning newly cached tenant {}.", tenant.getOrganizationId());
        tryMigrate(tenant);
    }

    /**
     * Retries the unhealthy tenants whose backoff has elapsed. Forces a Vault re-read first so an
     * operator's fix is picked up on THIS pass (a changed Postgres uri fires no {@link
     * TenantAddedEvent}, so this loop is the only path that re-migrates it).
     */
    @Scheduled(fixedRateString = "${datahub.cleanup.migration.sweep-rate:PT1M}")
    public void sweep() {
        if (!props.isEnabled() || unhealthy.isEmpty()) {
            return;
        }
        tenantConfigService.refreshCache();
        Instant now = Instant.now();
        int due = 0;
        for (String orgId : Set.copyOf(unhealthy.keySet())) {
            Unhealthy state = unhealthy.get(orgId);
            if (state == null || now.isBefore(state.nextRetryAt())) {
                continue; // recovered elsewhere, or still backing off
            }
            due++;
            Tenant tenant = tenantConfigService.cachedTenants.get(orgId);
            if (tenant == null) {
                // Gone from Vault — nothing left to migrate, stop tracking it.
                unhealthy.remove(orgId);
                continue;
            }
            tryMigrate(tenant);
        }
        if (due > 0) {
            logUnhealthy();
        }
    }

    /** Snapshot of currently-unhealthy tenants and their backoff state, for observability. */
    public Map<String, Unhealthy> getUnhealthyTenants() {
        return Map.copyOf(unhealthy);
    }

    private boolean tryMigrate(Tenant tenant) {
        String orgId = tenant.getOrganizationId();
        try {
            migrationService.migrate(tenant);
            if (unhealthy.remove(orgId) != null) {
                log.info("Tenant {} recovered — migration succeeded.", orgId);
            }
            return true;
        } catch (Exception e) {
            markFailed(tenant, e);
            return false;
        }
    }

    private void markFailed(Tenant tenant, Exception cause) {
        String orgId = tenant.getOrganizationId();
        Unhealthy state = unhealthy.compute(orgId, (id, prev) -> {
            int failures = prev == null ? 1 : prev.failures() + 1;
            return new Unhealthy(failures, Instant.now().plus(backoffFor(failures)));
        });
        log.error("Migration failed for tenant {} ({}) — attempt #{}, next retry after {}. "
                        + "Fix its config (e.g. the Postgres 'uri' in Vault) and it recovers automatically. Cause: {}",
                tenant.getOrganizationName(), orgId, state.failures(), state.nextRetryAt(), cause.getMessage(), cause);
    }

    /**
     * Exponential backoff for the n-th consecutive failure: {@code baseBackoff * 2^(n-1)}, capped at
     * {@code maxBackoff}. Package-private for unit testing. The shift is guarded so a large failure
     * count can't overflow — it saturates at the cap instead.
     */
    Duration backoffFor(int failures) {
        Duration base = props.getBaseBackoff();
        Duration max = props.getMaxBackoff();
        if (failures <= 1) {
            return base.compareTo(max) > 0 ? max : base;
        }
        // 2^(failures-1) via doubling, bailing out to the cap the moment we reach or exceed it.
        Duration backoff = base;
        for (int i = 1; i < failures; i++) {
            backoff = backoff.multipliedBy(2);
            if (backoff.compareTo(max) >= 0) {
                return max;
            }
        }
        return backoff;
    }

    private void logUnhealthy() {
        if (unhealthy.isEmpty()) {
            return;
        }
        String detail = unhealthy.entrySet().stream()
                .map(e -> e.getKey() + "(fails=" + e.getValue().failures()
                        + ", nextRetry=" + e.getValue().nextRetryAt() + ")")
                .collect(Collectors.joining(", "));
        log.warn("{} tenant(s) still unhealthy: {}", unhealthy.size(), detail);
    }
}
