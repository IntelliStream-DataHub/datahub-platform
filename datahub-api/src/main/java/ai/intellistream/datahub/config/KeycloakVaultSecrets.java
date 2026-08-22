// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;

import java.util.Map;

/**
 * The JWT issuer the api validates tokens against. Only the api needs it, so only the api passes
 * this contributor to {@link VaultConfigurationLoader}; the headless consumers do not.
 */
public final class KeycloakVaultSecrets implements VaultSecretContributor {

    @Override
    public void contribute(Vault vault, VaultProperties properties, Map<String, Object> out) throws VaultException {
        // The Keycloak issuer is the single shared atom between the api (JWT resource server)
        // and the console (OAuth2 client), so it lives in the cross-application
        // "datahub-platform" secret rather than being duplicated per module.
        var vaultData = vault.logical().read(properties.secretName() + "/datahub-platform").getData();
        String issuer = vaultData.get("keycloak.issuer");
        out.put("keycloak.issuers[0].uri", issuer);
        out.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer);
        out.put("keycloak.issuers[0].username-json-path", "$.preferred_username");
        out.put("keycloak.issuers[0].claims[0].jsonPath", "$.realm_access.roles");
        out.put("keycloak.issuers[0].claims[1].jsonPath", "$.resource_access.*.roles");
    }
}
