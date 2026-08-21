// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.config.TenantFlywayMigrator;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The unknown-tenant case used to reach the caller as a bodyless 500 from deep inside JPA
 * ("Could not open JPA EntityManager for transaction"), because the routing datasource only raises
 * it once a controller has opened a transaction — and by then a controller's blanket
 * {@code catch (RuntimeException)} has flattened it. These pin the refusal at the filter, before
 * any of that.
 */
class TenantProvisioningFilterTest {

    private static final String KNOWN = "2c5e2e73-2c2e-4516-ab58-4e602e1c495b";
    private static final String UNKNOWN = "ee798389-f522-4e5a-8560-efd83aec61a8";

    private final TenantConfigService configService = mock(TenantConfigService.class);
    private final TenantFlywayMigrator migrator = mock(TenantFlywayMigrator.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/resources/create");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private TenantProvisioningFilter filter() {
        return new TenantProvisioningFilter(configService, migrator);
    }

    @Test
    void refusesAnUnknownTenantWithForbidden() throws Exception {
        TenantContext.setTenantId(UNKNOWN);
        when(configService.getConfig(UNKNOWN)).thenReturn(null);

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getStatus()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * The org is not coming back on its own, so the caller must not be told to retry — that is what
     * separates this from the not-yet-provisioned 503 below.
     */
    @Test
    void doesNotInviteARetryForAnUnknownTenant() throws Exception {
        TenantContext.setTenantId(UNKNOWN);
        when(configService.getConfig(UNKNOWN)).thenReturn(null);

        filter().doFilter(request, response, chain);

        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    /** An unknown id misses the config cache and forces a Vault re-read; don't pay it twice. */
    @Test
    void doesNotAskTheMigratorAboutAnUnknownTenant() throws Exception {
        TenantContext.setTenantId(UNKNOWN);
        when(configService.getConfig(UNKNOWN)).thenReturn(null);

        filter().doFilter(request, response, chain);

        verify(migrator, never()).ensureProvisioned(any());
    }

    @Test
    void refusesAKnownButUnprovisionedTenantWithServiceUnavailable() throws Exception {
        TenantContext.setTenantId(KNOWN);
        when(configService.getConfig(KNOWN)).thenReturn(new Tenant());
        when(migrator.ensureProvisioned(KNOWN)).thenReturn(false);

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesAKnownProvisionedTenantThrough() throws Exception {
        TenantContext.setTenantId(KNOWN);
        when(configService.getConfig(KNOWN)).thenReturn(new Tenant());
        when(migrator.ensureProvisioned(KNOWN)).thenReturn(true);

        filter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    /** Public endpoints (session, swagger, the live-tail handshake) carry no tenant. */
    @Test
    void passesARequestWithNoTenantThrough() throws Exception {
        filter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        verify(migrator, never()).ensureProvisioned(any());
    }

    /** Where per-tenant migration is off there is no migrator, but unknown orgs are still refused. */
    @Test
    void appliesTheForbiddenGateWithNoMigrator() throws Exception {
        TenantContext.setTenantId(UNKNOWN);
        when(configService.getConfig(UNKNOWN)).thenReturn(null);

        new TenantProvisioningFilter(configService, null).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void passesAKnownTenantThroughWithNoMigrator() throws Exception {
        TenantContext.setTenantId(KNOWN);
        when(configService.getConfig(KNOWN)).thenReturn(new Tenant());

        new TenantProvisioningFilter(configService, null).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }
}
