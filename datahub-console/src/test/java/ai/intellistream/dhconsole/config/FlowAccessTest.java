// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.dhconsole.security.UserSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowAccessTest {

    private final TenantConfigService tenants = mock(TenantConfigService.class);
    private final UserSession session = new UserSession();
    private final FlowAccess access = new FlowAccess(tenants, session);

    private void tenantWithFlowFlag(Boolean flow) {
        session.setOrganizationId("org-1");
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(flow);
        Tenant tenant = new Tenant();
        tenant.setFeatures(features);
        when(tenants.getConfig("org-1")).thenReturn(tenant);
    }

    @Test
    void theFlagDecides() {
        tenantWithFlowFlag(true);
        assertThat(access.available()).isTrue();

        tenantWithFlowFlag(false);
        assertThat(access.available()).isFalse();
    }

    @Test
    void noOrganizationOrNoTenantMeansNoFlows() {
        assertThat(access.available()).isFalse();

        session.setOrganizationId("unknown");
        assertThat(access.available()).isFalse();
    }

    @Test
    void anErrorHidesTheEntryRatherThanFailingThePage() {
        session.setOrganizationId("org-1");
        when(tenants.getConfig("org-1")).thenThrow(new IllegalStateException("vault down"));
        assertThat(access.available()).isFalse();
    }
}
