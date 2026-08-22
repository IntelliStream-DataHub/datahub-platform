// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import org.springframework.core.env.PropertyResolver;

/**
 * The {@code vault.*} connection settings every application uses to reach HashiCorp Vault.
 *
 * <p>Resolved through {@link PropertyResolver#getProperty}, so each key can come from
 * {@code application.yml}, {@code application.properties} or the environment with Spring's usual
 * relaxed binding ({@code vault.keystore-password} is {@code VAULT_KEYSTORE_PASSWORD} as an
 * environment variable) and the usual precedence, environment over files.
 *
 * <ul>
 *   <li>{@code vault.address}, {@code vault.role-id}, {@code vault.secret-id}: AppRole login.</li>
 *   <li>{@code vault.secret-name}: the KV v2 mount the secrets live under.</li>
 *   <li>{@code vault.keystore} / {@code vault.keystore-password}: a PKCS12 (or JKS, by the
 *       {@code .jks} extension) file holding the client certificate and key presented to a Vault
 *       listener that requires mutual TLS. The password defaults to empty for a passwordless
 *       PKCS12.</li>
 *   <li>{@code vault.truststore} / {@code vault.truststore-password}: optional store holding the CA
 *       that signed Vault's server certificate. When unset the JVM's default trust applies.</li>
 * </ul>
 *
 * @param keystore absolute or working-directory-relative path, or {@code null} when unset
 * @param truststore absolute or working-directory-relative path, or {@code null} when unset
 */
public record VaultProperties(String address,
                              String roleId,
                              String secretId,
                              String secretName,
                              String keystore,
                              String keystorePassword,
                              String truststore,
                              String truststorePassword) {

    public static final String DEFAULT_SECRET_NAME = "intellistream-datahub";

    /**
     * Reads the {@code vault.*} keys and fails fast with a message naming the missing key, instead
     * of the driver's NullPointerException on a null address further down.
     */
    public static VaultProperties from(PropertyResolver resolver) {
        var properties = new VaultProperties(
                blankToNull(resolver.getProperty("vault.address")),
                blankToNull(resolver.getProperty("vault.role-id")),
                blankToNull(resolver.getProperty("vault.secret-id")),
                resolver.getProperty("vault.secret-name", DEFAULT_SECRET_NAME),
                blankToNull(resolver.getProperty("vault.keystore")),
                resolver.getProperty("vault.keystore-password", ""),
                blankToNull(resolver.getProperty("vault.truststore")),
                resolver.getProperty("vault.truststore-password", ""));
        properties.requireConnectionSettings();
        return properties;
    }

    /** Settings without TLS material, for contexts that only need to know where Vault is. */
    public static VaultProperties of(String address, String roleId, String secretId) {
        return new VaultProperties(address, roleId, secretId, DEFAULT_SECRET_NAME,
                null, "", null, "");
    }

    /** True when a keystore or truststore is configured, i.e. a custom SSL context is needed. */
    public boolean tlsConfigured() {
        return keystore != null || truststore != null;
    }

    private void requireConnectionSettings() {
        require(address, "vault.address");
        require(roleId, "vault.role-id");
        require(secretId, "vault.secret-id");
    }

    private static void require(String value, String key) {
        if (value == null) {
            throw new IllegalStateException("Vault is not configured: " + key
                    + " is missing (set it in application.yml/.properties or as the "
                    + key.toUpperCase().replace('.', '_').replace('-', '_')
                    + " environment variable)");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public String toString() {
        // Never print the AppRole secret or store passwords.
        return "VaultProperties[address=" + address + ", roleId=" + roleId
                + ", secretName=" + secretName + ", keystore=" + keystore
                + ", truststore=" + truststore + "]";
    }
}
