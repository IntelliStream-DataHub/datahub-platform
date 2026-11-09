// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.security.UserSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * How the tenant's values and the deployment's defaults combine is {@code LlmSettings.of}'s and
 * tested in commons. What is the console's is the one thing it adds: whose tenant.
 */
class ChatSettingsResolverTest {

    @Test
    void aTenantWithNoModelOfItsOwnGetsNoSettingsRatherThanSomeoneElses() {
        // ChatAccess has already hidden the panel by here, so this is the race — the secret emptied
        // mid-session — and it must fail rather than quietly find a credential somewhere.
        ChatSettingsResolver resolver = new ChatSettingsResolver(new ChatProperties(),
                mock(TenantConfigService.class), new UserSession());

        assertThatThrownBy(resolver::forCurrentTenant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-config");
    }
}
