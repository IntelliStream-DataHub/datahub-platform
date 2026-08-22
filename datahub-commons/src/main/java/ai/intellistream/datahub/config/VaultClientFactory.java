// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.SslConfig;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.VaultException;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Locale;
import java.util.Optional;

/**
 * The single place the platform opens a connection to Vault. Applies the optional client keystore
 * and truststore from {@link VaultProperties} so that every caller, the startup loader and the
 * runtime tenant refresh alike, can reach a Vault listener that requires mutual TLS.
 */
public final class VaultClientFactory {

    private VaultClientFactory() {
    }

    /**
     * AppRole login. Returns a client whose token is already set, talking KV engine v2.
     *
     * @throws IllegalStateException when the login fails; the message names the address but never
     *                               the credentials
     */
    public static Vault login(VaultProperties properties) {
        try {
            var config = new VaultConfig()
                    .address(properties.address())
                    .engineVersion(2);
            sslConfig(properties).ifPresent(config::sslConfig);
            config.build();

            var token = Vault.create(config).auth().loginByAppRole(properties.roleId(), properties.secretId());
            config.token(token.getAuthClientToken());
            return Vault.create(config);
        } catch (VaultException e) {
            throw new IllegalStateException("Vault AppRole login at " + properties.address() + " failed: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The driver-level SSL settings, built from the configured keystore/truststore. Empty when
     * neither is set, in which case the driver keeps its defaults (JVM trust, plus its own
     * {@code VAULT_SSL_VERIFY} / {@code VAULT_SSL_CERT} environment variables).
     */
    public static Optional<SslConfig> sslConfig(VaultProperties properties) {
        if (!properties.tlsConfigured()) {
            return Optional.empty();
        }
        try {
            var ssl = new SslConfig();
            if (properties.keystore() != null) {
                ssl.keyStore(loadKeyStore("vault.keystore", properties.keystore(), properties.keystorePassword()),
                        properties.keystorePassword());
            }
            if (properties.truststore() != null) {
                ssl.trustStore(loadKeyStore("vault.truststore", properties.truststore(),
                        properties.truststorePassword()));
            }
            return Optional.of(ssl.build());
        } catch (VaultException e) {
            throw new IllegalStateException("Could not build the Vault TLS context from vault.keystore/vault.truststore: "
                    + e.getMessage(), e);
        }
    }

    /** The same SSL context as {@link #sslConfig}, for callers that speak to Vault over a plain {@code HttpClient}. */
    public static Optional<SSLContext> sslContext(VaultProperties properties) {
        return sslConfig(properties).map(SslConfig::getSslContext);
    }

    private static KeyStore loadKeyStore(String key, String path, String password) {
        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new IllegalStateException(key + " " + file.toAbsolutePath() + " does not exist or is not readable");
        }
        // PKCS12 is the JDK default and what openssl/keytool produce today; JKS only by extension.
        String type = path.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password.toCharArray());
            return store;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not load " + key + " " + file.toAbsolutePath() + " as " + type
                    + ": " + e.getMessage(), e);
        }
    }
}
