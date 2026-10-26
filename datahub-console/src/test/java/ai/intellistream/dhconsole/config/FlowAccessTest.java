// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.tenant.TenantLlm;
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
    void agentsNeedTheFlagAndAUsableModel() {
        tenantWithFlowFlag(true);
        assertThat(access.agentsAvailable()).as("no model").isFalse();
        TenantLlm llm = new TenantLlm();
        llm.setProvider(LlmProvider.OPENAI_COMPATIBLE);
        llm.setModel("qwen");
        tenants.getConfig("org-1").setLlm(llm);
        assertThat(access.agentsAvailable()).as("no endpoint: not usable").isFalse();
        llm.setBaseUrl("http://127.0.0.1:11434/v1");
        assertThat(access.agentsAvailable()).isTrue();
        tenantWithFlowFlag(false);
        tenants.getConfig("org-1").setLlm(llm);
        assertThat(access.agentsAvailable()).as("the flag still decides").isFalse();
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
