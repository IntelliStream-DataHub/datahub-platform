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
 * Mutual TLS for the scrape, sourced from Vault.
 *
 * <p>The case that matters most is the one that contributes nothing. Metrics ship switched off and
 * this is optional on top of that, so a deployment with no {@code metrics.keystore} has to come out
 * of here untouched: emitting a half-configured {@code ssl.enabled=true} would stop the port
 * serving anything and would do it at startup, on hosts that never asked for TLS.
 */
class MetricsTlsVaultSecretsTest {

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
        new MetricsTlsVaultSecrets(MetricsTlsVaultSecrets.MANAGEMENT_SSL)
                .contribute(vaultReturning(secret), PROPS, out);
        return out;
    }

    @Test
    void aSecretWithoutAKeystoreContributesNothing() throws Exception {
        assertThat(contribute(Map.of("keycloak.issuer", "https://sso.example.com"))).isEmpty();
    }

    @Test
    void aBlankKeystoreIsTreatedAsUnset() throws Exception {
        assertThat(contribute(Map.of("metrics.keystore", "   "))).isEmpty();
    }

    @Test
    void keystoreAndTruststoreProduceAClientCertificateRequirement() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "metrics.keystore", "/etc/datahub/metrics-server.p12",
                "metrics.keystore-password", "server-secret",
                "metrics.truststore", "/etc/datahub/metrics-ca.p12",
                "metrics.truststore-password", "ca-secret"));

        assertThat(out).containsEntry("management.server.ssl.enabled", "true")
                .containsEntry("management.server.ssl.key-store", "/etc/datahub/metrics-server.p12")
                .containsEntry("management.server.ssl.key-store-password", "server-secret")
                .containsEntry("management.server.ssl.trust-store", "/etc/datahub/metrics-ca.p12")
                .containsEntry("management.server.ssl.trust-store-password", "ca-secret")
                // The whole point: a scrape without a certificate is refused.
                .containsEntry("management.server.ssl.client-auth", "need");
    }

    @Test
    void withoutATruststoreNoCertificateIsDemanded() throws Exception {
        // There would be nothing to verify a client certificate against, so asking for one would
        // refuse every scrape. Serve HTTPS and say so, rather than failing closed on a typo.
        Map<String, Object> out = contribute(Map.of(
                "metrics.keystore", "/etc/datahub/metrics-server.p12",
                "metrics.keystore-password", "server-secret"));

        assertThat(out).containsEntry("management.server.ssl.enabled", "true")
                .doesNotContainKey("management.server.ssl.client-auth")
                .doesNotContainKey("management.server.ssl.trust-store");
    }

    @Test
    void storeTypeFollowsTheFileExtension() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "metrics.keystore", "/etc/datahub/metrics.JKS",
                "metrics.truststore", "/etc/datahub/ca.p12"));

        assertThat(out).containsEntry("management.server.ssl.key-store-type", "JKS")
                .containsEntry("management.server.ssl.trust-store-type", "PKCS12");
    }

    @Test
    void clientAuthCanBeRelaxedDeliberately() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "metrics.keystore", "/etc/datahub/metrics-server.p12",
                "metrics.truststore", "/etc/datahub/metrics-ca.p12",
                "metrics.client-auth", "OPTIONAL"));

        assertThat(out).containsEntry("management.server.ssl.client-auth", "optional");
    }

    @Test
    void aPasswordlessStoreOmitsThePasswordRatherThanSendingBlank() throws Exception {
        Map<String, Object> out = contribute(Map.of(
                "metrics.keystore", "/etc/datahub/metrics-server.p12",
                "metrics.keystore-password", ""));

        assertThat(out).containsKey("management.server.ssl.key-store")
                .doesNotContainKey("management.server.ssl.key-store-password");
    }

    @Test
    void theHeadlessServicesGetTheirOwnPrefix() throws Exception {
        Map<String, Object> out = new HashMap<>();
        new MetricsTlsVaultSecrets(MetricsTlsVaultSecrets.SERVER_SSL).contribute(
                vaultReturning(Map.of("metrics.keystore", "/etc/datahub/metrics-server.p12")),
                PROPS, out);

        assertThat(out).containsKey("server.ssl.key-store")
                .doesNotContainKey("management.server.ssl.key-store");
    }
}
