// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;

/**
 * Mutual TLS for the Prometheus scrape, with the store passwords held in Vault rather than on disk.
 *
 * <p>The scrape endpoint has no login, so the only thing that can keep it to the monitoring host is
 * a certificate requirement. Spring Boot already implements that: point {@code *.ssl.key-store} and
 * {@code *.ssl.trust-store} at PKCS12 files and set {@code client-auth: need}. What Boot has no
 * answer for is where the two passwords live, and a keystore password sitting in a configuration
 * file on every application host is most of the way to no password at all. So the paths and
 * passwords come from the same Vault secret as the Keycloak issuer, and reach Boot as ordinary
 * properties before any bean binds to them.
 *
 * <p>Entirely optional. A secret with no {@code metrics.keystore} contributes nothing, the port
 * stays plain HTTP, and a deployment that has not set this up is unaffected. That matters because
 * the scrape also ships switched off: requiring a certificate to enable metrics at all would make
 * an unconfigured install broken rather than safe.
 *
 * <p>The property prefix differs by service and is given by the caller: the api, console and
 * analysis serve metrics on a management port of their own ({@code management.server.ssl}), while
 * the consumers and cleanup are headless and their only port is the metrics port
 * ({@code server.ssl}).
 */
@Slf4j
public final class MetricsTlsVaultSecrets implements VaultSecretContributor {

    /** What the api, console and analysis pass: metrics live on a management port of their own. */
    public static final String MANAGEMENT_SSL = "management.server.ssl";

    /** What the headless services pass: their only port is the metrics port. */
    public static final String SERVER_SSL = "server.ssl";

    private final String prefix;

    public MetricsTlsVaultSecrets(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public void contribute(Vault vault, VaultProperties properties, Map<String, Object> out)
            throws VaultException {
        // Same cross-application secret as the Keycloak issuer: one certificate authority and one
        // scrape credential serve every service, so duplicating them per module would only create
        // six places to rotate.
        Map<String, String> data = vault.logical()
                .read(properties.secretName() + "/datahub-platform").getData();

        String keystore = blankToNull(data.get("metrics.keystore"));
        if (keystore == null) {
            log.debug("No metrics.keystore in Vault; the scrape port stays plain HTTP.");
            return;
        }
        String truststore = blankToNull(data.get("metrics.truststore"));

        out.put(prefix + ".enabled", "true");
        out.put(prefix + ".key-store", keystore);
        out.put(prefix + ".key-store-type", storeType(keystore));
        putIfPresent(out, prefix + ".key-store-password", data.get("metrics.keystore-password"));

        if (truststore != null) {
            // Without a truststore there is nothing to verify a client certificate against, so
            // demanding one would refuse every scrape. Server-side TLS alone is still worth having.
            out.put(prefix + ".trust-store", truststore);
            out.put(prefix + ".trust-store-type", storeType(truststore));
            putIfPresent(out, prefix + ".trust-store-password", data.get("metrics.truststore-password"));
            out.put(prefix + ".client-auth", clientAuth(data.get("metrics.client-auth")));
        } else {
            log.warn("metrics.keystore is set but metrics.truststore is not: the scrape port will "
                    + "serve HTTPS without asking the caller for a certificate.");
        }
    }

    /**
     * {@code need} unless the secret says otherwise. A truststore was configured, so the intent is
     * to check callers; {@code optional} accepts a caller that presents nothing, which is the one
     * setting that looks secure and is not.
     */
    private static String clientAuth(String configured) {
        String value = blankToNull(configured);
        return value == null ? "need" : value.toLowerCase(Locale.ROOT);
    }

    /** PKCS12 is what keytool and openssl produce today; JKS only when the name says so. */
    private static String storeType(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (blankToNull(value) != null) {
            out.put(key, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
