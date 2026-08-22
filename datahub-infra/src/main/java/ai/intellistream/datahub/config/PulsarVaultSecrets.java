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
        out.put("pulsar.issuer-url", vaultData.get("pulsar.issuer-url"));
        out.put("pulsar.internal-tenant", vaultData.get("pulsar.internal-tenant"));
        out.put("pulsar.service.url", "pulsar+ssl://" + host + ":6651");
        out.put("pulsar.service.httpUrl", "https://" + host + ":8443");
    }
}
