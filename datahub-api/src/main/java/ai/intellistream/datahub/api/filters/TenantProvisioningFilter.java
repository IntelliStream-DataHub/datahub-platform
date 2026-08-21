// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.config.TenantFlywayMigrator;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gates every request on its tenant being both <em>known</em> and <em>provisioned</em>, before it
 * reaches a controller. Runs inside the Spring Security chain, AFTER authentication/authorization,
 * because that is where {@code OrganizationValidator} sets {@link TenantContext} from the JWT — see
 * {@code SecurityConfig#filterChain}.
 *
 * <p>Two refusals, deliberately different:
 * <ul>
 *   <li><b>403</b> — no tenant record exists for the token's organization. The caller authenticated
 *       fine but this deployment has never heard of the org (or it was removed from Vault), so
 *       there is no database to route to and no amount of retrying helps.</li>
 *   <li><b>503</b> — the tenant is known but its migration is still failing. The request is refused
 *       rather than routed to a half-provisioned schema; datahub-cleanup's sweep heals it and a
 *       later request re-verifies against the tenant DB.</li>
 * </ul>
 *
 * <p>Refusing here rather than downstream is what makes the 403 reachable at all: the routing
 * datasource raises {@code UnknownTenantException} only once a controller has opened a transaction,
 * by which point several controllers' blanket {@code catch (RuntimeException)} have flattened it
 * into a bodyless 500. It also halves the cost of the bad request — an unknown id misses
 * {@code TenantConfigService}'s cache and forces a Vault re-read, and letting the request continue
 * to the datasource pays that a second time.
 *
 * <p>For an org already confirmed in this JVM the provisioning check is a cheap set lookup; the
 * first request for a not-yet-confirmed org pays one idempotent Flyway round-trip (fast when the
 * schema is already current).
 */
@Slf4j
public class TenantProvisioningFilter extends OncePerRequestFilter {

    private final TenantConfigService configService;

    /** Absent when {@code datahub.flyway.per-tenant-migrate} is off; the 403 gate still applies. */
    private final @Nullable TenantFlywayMigrator migrator;

    public TenantProvisioningFilter(TenantConfigService configService, @Nullable TenantFlywayMigrator migrator) {
        this.configService = configService;
        this.migrator = migrator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = TenantContext.getTenantId();
        // No tenant on the thread means a public/permit-all endpoint (session, swagger, live-tail
        // handshake) — nothing to check, let it through.
        if (tenantId == null) {
            chain.doFilter(request, response);
            return;
        }

        if (configService.getConfig(tenantId) == null) {
            log.warn("Refusing request for unknown tenant {} — no tenant record for that "
                    + "organization. Path: {}", tenantId, request.getRequestURI());
            response.sendError(HttpStatus.FORBIDDEN.value(),
                    "Unknown organization: this deployment has no tenant for the organization in "
                            + "your token.");
            return;
        }

        if (migrator != null && !migrator.ensureProvisioned(tenantId)) {
            log.warn("Refusing request for tenant {} — schema not yet provisioned (migration failing). "
                    + "Path: {}", tenantId, request.getRequestURI());
            response.setHeader(HttpHeaders.RETRY_AFTER, "30");
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Tenant provisioning in progress; retry shortly.");
            return;
        }

        chain.doFilter(request, response);
    }
}
