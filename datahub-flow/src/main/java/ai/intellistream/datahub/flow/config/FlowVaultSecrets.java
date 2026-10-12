// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.config.VaultProperties;
import ai.intellistream.datahub.config.VaultSecretContributor;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;

import java.util.Map;

/**
 * Publishes the Keycloak issuer from the shared {@code datahub-platform} secret as
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, so this service validates callers'
 * tokens against the same issuer as the api.
 *
 * <p>Registered in {@code DatahubFlowApplication.main}, so it runs on real startup but not in
 * {@code @SpringBootTest} contexts, which supply a dummy issuer instead.
 */
public final class FlowVaultSecrets implements VaultSecretContributor {

    @Override
    public void contribute(Vault vault, VaultProperties properties, Map<String, Object> out)
            throws VaultException {
        var data = vault.logical()
                .read(properties.secretName() + "/datahub-platform").getData();
        out.put("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                data.get("keycloak.issuer"));
    }
}
