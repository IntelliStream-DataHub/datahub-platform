// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.config;

import ai.intellistream.datahub.config.VaultProperties;
import ai.intellistream.datahub.config.VaultSecretContributor;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;

import java.util.Map;

/**
 * Fetches the Keycloak issuer from Vault and publishes it as
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, so this service validates forwarded
 * user JWTs against the same issuer as the api: no issuer duplicated in config, no separate secret.
 *
 * <p>This is the only Vault data this lean service needs; it has no DB, Pulsar, or session
 * store.
 * Registered in {@code DatahubAnalysisApplication.main} (not {@code spring.factories}), so it
 * runs on real startup but not in {@code @SpringBootTest} contexts, which supply a dummy issuer
 * instead.
 */
public final class AnalysisVaultSecrets implements VaultSecretContributor {

    @Override
    public void contribute(Vault vault, VaultProperties properties, Map<String, Object> out)
            throws VaultException {
        // The Keycloak issuer is the single shared atom between the api (JWT resource server)
        // and this service; it lives in the cross-application "datahub-platform" secret.
        var data = vault.logical()
                .read(properties.secretName() + "/datahub-platform").getData();
        out.put("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                data.get("keycloak.issuer"));
    }
}
