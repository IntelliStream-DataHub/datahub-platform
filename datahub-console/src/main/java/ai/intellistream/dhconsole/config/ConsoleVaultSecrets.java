// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.config.VaultProperties;
import ai.intellistream.datahub.config.VaultSecretContributor;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;

import java.util.Map;

/**
 * The console's Vault-backed configuration: the OAuth2 login client, the Spring Session Valkey
 * store, the api address and the optional chat panel settings.
 */
public final class ConsoleVaultSecrets implements VaultSecretContributor {

    @Override
    public void contribute(Vault vault, VaultProperties properties, Map<String, Object> vaultMap)
            throws VaultException {
        // Console-owned config (OAuth2 login client + the Spring Session Valkey store) lives in
        // the "datahub-console" secret, fields namespaced by subsystem (oauth.*,
        // http.session.valkey.*). The Keycloak issuer is shared with the api, so it is read once
        // from the cross-application "datahub-platform" secret rather than duplicated here.
        var consoleData = vault.logical()
                .read(properties.secretName() + "/datahub-console").getData();
        var platformData = vault.logical()
                .read(properties.secretName() + "/datahub-platform").getData();

        var clientId = consoleData.get("oauth.client-id");
        var clientSecret = consoleData.get("oauth.client-secret");
        var clientName = consoleData.get("oauth.client-name");
        var provider = consoleData.get("oauth.provider");
        var scope = consoleData.get("oauth.scope");
        var grantType = consoleData.get("oauth.grant-type");
        var redirectUri = consoleData.get("oauth.redirect-uri");
        var realmRolesJsonPath = consoleData.get("oauth.realm-roles-jsonpath");
        var clientRolesJsonPath = consoleData.get("oauth.client-roles-jsonpath");
        var datahubUrl = consoleData.get("console.datahub-url");

        var issuerUri = platformData.get("keycloak.issuer");

        vaultMap.put("spring.security.oauth2.client.registration.keycloak.client-name", clientName);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.client-id", clientId);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.client-secret",
                clientSecret);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.provider", provider);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.scope", scope);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.authorization-grant-type",
                grantType);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.redirect-uri",
                redirectUri);
        vaultMap.put("spring.security.oauth2.client.provider.keycloak.issuer-uri", issuerUri);

        vaultMap.put("authorities-mapping.issuers[0].uri", issuerUri);
        vaultMap.put("authorities-mapping.issuers[0].claims[0].jsonPath", realmRolesJsonPath);
        vaultMap.put("authorities-mapping.issuers[0].claims[1].jsonPath", clientRolesJsonPath);

        vaultMap.put("datahub.url", datahubUrl);

        vaultMap.put("spring.data.redis.host", consoleData.get("http.session.valkey.host"));
        vaultMap.put("spring.data.redis.port", consoleData.get("http.session.valkey.port"));
        vaultMap.put("spring.data.redis.username", consoleData.get("http.session.valkey.user"));
        vaultMap.put("spring.data.redis.password", consoleData.get("http.session.valkey.password"));

        // Chat panel. Which model, and on whose credential, is not here: that is per tenant, in
        // tenant-config/<org-name>. These are the defaults a tenant lands on when it configures a
        // model but says nothing about how to run it — not ceilings, since a tenant on its own
        // credential pays its own bill and may override every one of them.
        //
        // Absent from the secret in deployments that have not enabled chat, in which case these bind
        // to null and ChatProperties keeps its own defaults. Do not give them defaults in
        // application.properties instead, because this property source is registered with addLast
        // and would be shadowed by them.
        putIfPresent(vaultMap, "datahub.chat.effort", consoleData.get("llm.effort"));
        putIfPresent(vaultMap, "datahub.chat.max-output-tokens",
                consoleData.get("llm.max-output-tokens"));
        putIfPresent(vaultMap, "datahub.chat.max-iterations",
                consoleData.get("llm.max-iterations"));
        putIfPresent(vaultMap, "datahub.chat.turn-timeout", consoleData.get("llm.turn-timeout"));
        putIfPresent(vaultMap, "datahub.chat.instructions", consoleData.get("llm.instructions"));
    }

    private static void putIfPresent(Map<String, Object> vaultMap, String property, String value) {
        if (value != null && !value.isBlank()) {
            vaultMap.put(property, value);
        }
    }
}
