// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.security.UserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The access rule is the security boundary (the UI hide is only cosmetic), so pin every combination:
 * chat is available only when the tenant's Vault flag, the tenant's own model, and the user's
 * authority all agree.
 *
 * <p>The model is part of the rule because there is no deployment credential behind it. A tenant
 * that has configured none must see no panel — the alternative was an assistant answering on
 * whoever deployed the platform's account.
 */
class ChatAccessTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(String... authorities) {
        var granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "n/a", granted));
    }

    private static TenantConfigService tenantWithChat(boolean chatEnabled) {
        return tenantWith(chatEnabled, usableModel());
    }

    private static TenantConfigService tenantWith(boolean chatEnabled, TenantLlm llm) {
        Tenant tenant = new Tenant();
        tenant.getFeatures().setChatFeatureEnabled(chatEnabled);
        tenant.setLlm(llm);
        TenantConfigService service = mock(TenantConfigService.class);
        when(service.getConfig(ORG)).thenReturn(tenant);
        return service;
    }

    private static TenantLlm usableModel() {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(LlmProvider.ANTHROPIC);
        llm.setApiKey("tenant-key");
        llm.setModel("claude-opus-5");
        return llm;
    }

    private static UserSession sessionFor(String orgId) {
        UserSession session = new UserSession();
        session.setOrganizationId(orgId);
        return session;
    }

    @Test
    void availableWhenDeploymentTenantAndUserAllAllow() {
        authenticateWith("DATAHUB_CONSOLE", "DATAHUB_CHAT");
        assertThat(new ChatAccess(tenantWithChat(true), sessionFor(ORG)).available()).isTrue();
    }

    @Test
    void deniedWhenTheUserLacksTheChatAuthority() {
        authenticateWith("DATAHUB_CONSOLE");
        assertThat(new ChatAccess(tenantWithChat(true), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenTheTenantFlagIsOff() {
        authenticateWith("DATAHUB_CHAT");
        assertThat(new ChatAccess(tenantWithChat(false), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenThereIsNoAuthenticatedUser() {
        assertThat(new ChatAccess(tenantWithChat(true), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenTheTenantHasConfiguredNoModel() {
        // The flag is on and the user is entitled, but there is no credential to run on and none to
        // borrow. Denied, not "denied later, mid-conversation".
        authenticateWith("DATAHUB_CHAT");
        assertThat(new ChatAccess(tenantWith(true, null), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenTheTenantsModelIsOnlyHalfWritten() {
        authenticateWith("DATAHUB_CHAT");
        TenantLlm noKey = usableModel();
        noKey.setApiKey(null);

        assertThat(new ChatAccess(tenantWith(true, noKey), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenTheSessionHasNoOrganisation() {
        authenticateWith("DATAHUB_CHAT");
        assertThat(new ChatAccess(tenantWithChat(true), sessionFor(null)).available()).isFalse();
    }
}
