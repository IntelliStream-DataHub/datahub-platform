// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.VaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class VaultConfigurationLoader implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    public VaultConfigurationLoader() {
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();

        String vaultAddress = event.getEnvironment().getProperty("vault.address");
        String roleId = event.getEnvironment().getProperty("vault.role-id");
        String secretId = event.getEnvironment().getProperty("vault.secret-id");

        Map<String, Object> vaultMap = new HashMap<>();

        try {
            var config = new VaultConfig()
                    .address(vaultAddress)
                    .engineVersion(2)
                    .build();
            var v = Vault.create(config);
            var t = v.auth().loginByAppRole(roleId, secretId);
            config.token(t.getAuthClientToken());
            Vault vault = Vault.create(config);

            setVaultConfig(vault, vaultMap);

        } catch (VaultException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        MapPropertySource propertySource = new MapPropertySource("vaultProperties", vaultMap);
        environment.getPropertySources().addLast(propertySource);

    }

    private void setVaultConfig(Vault vault, Map<String, Object> vaultMap) throws VaultException {
        // Console-owned config (OAuth2 login client + the Spring Session Valkey store) lives in
        // the "datahub-console" secret, fields namespaced by subsystem (oauth.*,
        // http.session.valkey.*). The Keycloak issuer is shared with the api, so it is read once
        // from the cross-application "datahub-platform" secret rather than duplicated here.
        var consoleData = vault.logical().read("intellistream-datahub/datahub-console").getData();
        var platformData = vault.logical().read("intellistream-datahub/datahub-platform").getData();

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
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.client-secret", clientSecret);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.provider", provider);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.scope", scope);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.authorization-grant-type", grantType);
        vaultMap.put("spring.security.oauth2.client.registration.keycloak.redirect-uri", redirectUri);
        vaultMap.put("spring.security.oauth2.client.provider.keycloak.issuer-uri", issuerUri);

        vaultMap.put("authorities-mapping.issuers[0].uri", issuerUri);
        vaultMap.put("authorities-mapping.issuers[0].claims[0].jsonPath", realmRolesJsonPath);
        vaultMap.put("authorities-mapping.issuers[0].claims[1].jsonPath", clientRolesJsonPath);

        vaultMap.put("datahub.url", datahubUrl);

        vaultMap.put("spring.data.redis.host", consoleData.get("http.session.valkey.host"));
        vaultMap.put("spring.data.redis.port", consoleData.get("http.session.valkey.port"));
        vaultMap.put("spring.data.redis.username", consoleData.get("http.session.valkey.user"));
        vaultMap.put("spring.data.redis.password", consoleData.get("http.session.valkey.password"));

        // Chat panel. Absent from the secret in deployments that have not enabled chat, in which
        // case these bind to null and ChatProperties keeps its own defaults — do not give them
        // defaults in application.properties instead, because this property source is registered
        // with addLast and would be shadowed by them.
        putIfPresent(vaultMap, "datahub.chat.provider", consoleData.get("llm.provider"));
        putIfPresent(vaultMap, "datahub.chat.api-key", consoleData.get("llm.api-key"));
        putIfPresent(vaultMap, "datahub.chat.model", consoleData.get("llm.model"));
        putIfPresent(vaultMap, "datahub.chat.base-url", consoleData.get("llm.base-url"));

        // How the assistant is allowed to spend. A self-hosted model wants a turn budget in minutes
        // and reasoning turned off; a hosted one wants neither. Both are per-deployment facts, so
        // they belong beside the credentials rather than in a properties file on one machine.
        putIfPresent(vaultMap, "datahub.chat.effort", consoleData.get("llm.effort"));
        putIfPresent(vaultMap, "datahub.chat.reasoning-effort", consoleData.get("llm.reasoning-effort"));
        putIfPresent(vaultMap, "datahub.chat.max-output-tokens", consoleData.get("llm.max-output-tokens"));
        putIfPresent(vaultMap, "datahub.chat.turn-timeout", consoleData.get("llm.turn-timeout"));
        putIfPresent(vaultMap, "datahub.chat.instructions", consoleData.get("llm.instructions"));
    }

    private static void putIfPresent(Map<String, Object> vaultMap, String property, String value) {
        if (value != null && !value.isBlank()) {
            vaultMap.put(property, value);
        }
    }

}
