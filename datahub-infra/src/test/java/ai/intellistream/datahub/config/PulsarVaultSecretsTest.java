// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.api.Logical;
import io.github.jopenlibs.vault.response.LogicalResponse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pulsar credentials from the shared {@code datahub-platform} secret.
 *
 * <p>The issuer is the case that has two legitimate shapes: a broker on its own issuer, and a
 * deployment that authenticates Pulsar with the same Keycloak realm the API already names.
 */
class PulsarVaultSecretsTest {

    private static final VaultProperties PROPS =
            new VaultProperties("https://vault.example.com", "role", "secret", "mount",
                    null, "", null, "");

    private static Vault vaultReturning(Map<String, String> data) {
        Vault vault = mock(Vault.class);
        Logical logical = mock(Logical.class);
        LogicalResponse response = mock(LogicalResponse.class);
        when(vault.logical()).thenReturn(logical);
        try {
            when(logical.read(anyString())).thenReturn(response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(response.getData()).thenReturn(data);
        return vault;
    }

    private static Map<String, Object> contribute(Map<String, String> secret) throws Exception {
        Map<String, Object> out = new HashMap<>();
        new PulsarVaultSecrets().contribute(vaultReturning(secret), PROPS, out);
        return out;
    }

    @Test
    void anExplicitIssuerWinsOverTheKeycloakIssuer() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "pulsar.host", "pulsar.example.com",
                "pulsar.issuer-url", "https://pulsar-issuer.example.com",
                "keycloak.issuer", "https://sso.example.com/realms/datahub"));

        assertThat(out).containsEntry("pulsar.issuer-url", "https://pulsar-issuer.example.com")
                .containsEntry("pulsar.service.url", "pulsar+ssl://pulsar.example.com:6651")
                .containsEntry("pulsar.service.httpUrl", "https://pulsar.example.com:8443");
    }

    @Test
    void aMissingIssuerFallsBackToTheKeycloakIssuer() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "pulsar.host", "pulsar.example.com",
                "keycloak.issuer", "https://sso.example.com/realms/datahub"));

        assertThat(out).containsEntry("pulsar.issuer-url", "https://sso.example.com/realms/datahub");
    }

    @Test
    void aBlankIssuerFallsBackToTheKeycloakIssuer() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "pulsar.issuer-url", "   ",
                "keycloak.issuer", "https://sso.example.com/realms/datahub"));

        assertThat(out).containsEntry("pulsar.issuer-url", "https://sso.example.com/realms/datahub");
    }

    @Test
    void bothIssuersBlankStaysUnset() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "pulsar.issuer-url", "",
                "keycloak.issuer", "  "));

        assertThat(out).containsEntry("pulsar.issuer-url", null);
    }
}
