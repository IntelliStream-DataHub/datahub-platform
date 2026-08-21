// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;

import java.util.Map;

/**
 * Optional Vault loader for Keycloak/JWT properties. {@link VaultConfigurationLoader} in
 * datahub-infra looks this class up reflectively by FQCN — services that need JWT issuer
 * configuration (datahub-api) ship this class; headless consumers do not. The reflection
 * contract is the {@link #load(Vault, Map, String)} signature; do not change it without
 * updating the loader.
 */
public final class VaultKeycloakData {

    private VaultKeycloakData() {
    }

    public static void load(Vault vault, Map<String, Object> vaultMap, String secretName) throws VaultException {
        // The Keycloak issuer is the single shared atom between the api (JWT resource server)
        // and the console (OAuth2 client), so it lives in the cross-application
        // "datahub-platform" secret rather than being duplicated per module.
        var vaultData = vault.logical().read(secretName + "/datahub-platform").getData();
        String issuer = vaultData.get("keycloak.issuer");
        vaultMap.put("keycloak.issuers[0].uri", issuer);
        vaultMap.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer);
        vaultMap.put("keycloak.issuers[0].username-json-path", "$.preferred_username");
        vaultMap.put("keycloak.issuers[0].claims[0].jsonPath", "$.realm_access.roles");
        vaultMap.put("keycloak.issuers[0].claims[1].jsonPath", "$.resource_access.*.roles");
    }
}
