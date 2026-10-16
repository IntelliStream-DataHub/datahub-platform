// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;

import java.util.Map;

/**
 * Pulsar client and admin credentials for every application that owns a Pulsar client: the api,
 * both consumers and the cleanup app.
 */
public final class PulsarVaultSecrets implements VaultSecretContributor {

    @Override
    public void contribute(Vault vault, VaultProperties properties, Map<String, Object> out)
            throws VaultException {
        // Shared cross-application config lives in the "datahub-platform" secret, with each
        // field namespaced by subsystem (pulsar.*). Pulsar is global today; the per-tenant
        // pulsar block in the tenant registry stays unread until multi-cluster work lands.
        var vaultData = vault.logical()
                .read(properties.secretName() + "/datahub-platform").getData();
        String host = vaultData.get("pulsar.host");

        out.put("pulsar.client-id", vaultData.get("pulsar.client-id"));
        out.put("pulsar.client-secret", vaultData.get("pulsar.client-secret"));
        out.put("pulsar.admin-client-id", vaultData.get("pulsar.admin-client-id"));
        out.put("pulsar.admin-client-secret", vaultData.get("pulsar.admin-client-secret"));
        out.put("pulsar.host", host);
        // A broker that trusts the same realm as the API can omit pulsar.issuer-url.
        // An explicit value still wins when Pulsar trusts a different issuer.
        out.put("pulsar.issuer-url", issuerUrl(vaultData));
        out.put("pulsar.internal-tenant", vaultData.get("pulsar.internal-tenant"));
        out.put("pulsar.service.url", "pulsar+ssl://" + host + ":6651");
        out.put("pulsar.service.httpUrl", "https://" + host + ":8443");
    }

    /**
     * {@code pulsar.issuer-url} when it is set, otherwise {@code keycloak.issuer}. Blank is the
     * same as absent: a whitespace-only field must not win over the shared issuer.
     */
    private static String issuerUrl(Map<String, String> vaultData) {
        String issuerUrl = blankToNull(vaultData.get("pulsar.issuer-url"));
        return issuerUrl != null ? issuerUrl : blankToNull(vaultData.get("keycloak.issuer"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
