// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
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
 * chat is available only when the deployment, the tenant's Vault flag, and the user's authority all
 * agree.
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
        Tenant tenant = new Tenant();
        tenant.getFeatures().setChatFeatureEnabled(chatEnabled);
        TenantConfigService service = mock(TenantConfigService.class);
        when(service.getConfig(ORG)).thenReturn(tenant);
        return service;
    }

    private static UserSession sessionFor(String orgId) {
        UserSession session = new UserSession();
        session.setOrganizationId(orgId);
        return session;
    }

    @Test
    void availableWhenDeploymentTenantAndUserAllAllow() {
        authenticateWith("DATAHUB_CONSOLE", "DATAHUB_CHAT");
        assertThat(new ChatAccess(true, tenantWithChat(true), sessionFor(ORG)).available()).isTrue();
    }

    @Test
    void deniedWhenTheUserLacksTheChatAuthority() {
        authenticateWith("DATAHUB_CONSOLE");
        assertThat(new ChatAccess(true, tenantWithChat(true), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenTheTenantFlagIsOff() {
        authenticateWith("DATAHUB_CHAT");
        assertThat(new ChatAccess(true, tenantWithChat(false), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenTheDeploymentHasChatDisabled() {
        authenticateWith("DATAHUB_CHAT");
        assertThat(new ChatAccess(false, tenantWithChat(true), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenThereIsNoAuthenticatedUser() {
        assertThat(new ChatAccess(true, tenantWithChat(true), sessionFor(ORG)).available()).isFalse();
    }

    @Test
    void deniedWhenTheSessionHasNoOrganisation() {
        authenticateWith("DATAHUB_CHAT");
        assertThat(new ChatAccess(true, tenantWithChat(true), sessionFor(null)).available()).isFalse();
    }
}
