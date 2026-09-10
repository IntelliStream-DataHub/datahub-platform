// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code vault.*} keys resolve the same way whether they come from application.yml /
 * application.properties or from environment variables, with the environment winning, exactly as
 * for the existing {@code VAULT_ADDRESS} / {@code VAULT_ROLE_ID} / {@code VAULT_SECRET_ID}.
 */
class VaultPropertiesTest {

    private static final Map<String, Object> FILE_SETTINGS = Map.of(
            "vault.address", "https://vault.example.test:8200",
            "vault.role-id", "role",
            "vault.secret-id", "secret");

    @Test
    void readsKeystoreFromApplicationFile() {
        var props = VaultProperties.from(resolver(Map.of(), Map.of(
                "vault.address", "https://vault.example.test:8200",
                "vault.role-id", "role",
                "vault.secret-id", "secret",
                "vault.keystore", "/etc/datahub/vault-client.p12",
                "vault.keystore-password", "pw",
                "vault.truststore", "/etc/datahub/vault-ca.p12")));

        assertThat(props.keystore()).isEqualTo("/etc/datahub/vault-client.p12");
        assertThat(props.keystorePassword()).isEqualTo("pw");
        assertThat(props.truststore()).isEqualTo("/etc/datahub/vault-ca.p12");
        assertThat(props.truststorePassword()).isEmpty();
        assertThat(props.secretName()).isEqualTo(VaultProperties.DEFAULT_SECRET_NAME);
        assertThat(props.tlsConfigured()).isTrue();
    }

    @Test
    void readsKeystoreFromEnvironmentVariables() {
        var props = VaultProperties.from(resolver(Map.of(
                "VAULT_ADDRESS", "https://vault.example.test:8200",
                "VAULT_ROLE_ID", "role",
                "VAULT_SECRET_ID", "secret",
                "VAULT_KEYSTORE", "/run/secrets/client.p12",
                "VAULT_KEYSTORE_PASSWORD", "env-pw",
                "VAULT_TRUSTSTORE", "/run/secrets/ca.jks",
                "VAULT_TRUSTSTORE_PASSWORD", "ca-pw"), Map.of()));

        assertThat(props.address()).isEqualTo("https://vault.example.test:8200");
        assertThat(props.keystore()).isEqualTo("/run/secrets/client.p12");
        assertThat(props.keystorePassword()).isEqualTo("env-pw");
        assertThat(props.truststore()).isEqualTo("/run/secrets/ca.jks");
        assertThat(props.truststorePassword()).isEqualTo("ca-pw");
    }

    @Test
    void environmentOverridesApplicationFile() {
        var props = VaultProperties.from(resolver(
                Map.of("VAULT_KEYSTORE", "/from/env.p12"),
                withFileSettings("vault.keystore", "/from/file.p12")));

        assertThat(props.keystore()).isEqualTo("/from/env.p12");
    }

    @Test
    void noTlsSettingsByDefault() {
        var props = VaultProperties.from(resolver(Map.of(), FILE_SETTINGS));

        assertThat(props.keystore()).isNull();
        assertThat(props.truststore()).isNull();
        assertThat(props.tlsConfigured()).isFalse();
    }

    @Test
    void blankKeystoreCountsAsUnset() {
        // datahub-cleanup's application.yml binds `${VAULT_KEYSTORE:}`, i.e. "" when the variable
        // is absent.
        var props = VaultProperties.from(
                resolver(Map.of(), withFileSettings("vault.keystore", "")));

        assertThat(props.keystore()).isNull();
        assertThat(props.tlsConfigured()).isFalse();
    }

    @Test
    void missingAddressFailsWithTheKeyName() {
        assertThatThrownBy(() -> VaultProperties.from(resolver(Map.of(),
                Map.of("vault.role-id", "role", "vault.secret-id", "secret"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vault.address")
                .hasMessageContaining("VAULT_ADDRESS");
    }

    @Test
    void toStringNeverPrintsSecrets() {
        var props = new VaultProperties("https://v", "role", "the-secret", "mount",
                "/k.p12", "kpw", "/t.p12", "tpw");

        assertThat(props.toString())
                .doesNotContain("the-secret", "kpw", "tpw")
                .contains("https://v", "/k.p12");
    }

    private static Map<String, Object> withFileSettings(String key, Object value) {
        var all = new java.util.HashMap<>(FILE_SETTINGS);
        all.put(key, value);
        return all;
    }

    private static PropertySourcesPropertyResolver resolver(Map<String, Object> env,
                                                            Map<String, Object> file) {
        var sources = new MutablePropertySources();
        sources.addLast(new SystemEnvironmentPropertySource("systemEnvironment", env));
        sources.addLast(new MapPropertySource("application", file));
        return new PropertySourcesPropertyResolver(sources);
    }
}
