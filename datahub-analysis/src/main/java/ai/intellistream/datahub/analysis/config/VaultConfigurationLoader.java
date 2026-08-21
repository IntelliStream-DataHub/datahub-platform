// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.VaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Fetches the Keycloak issuer from Vault at startup and publishes it as
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, so this service validates forwarded
 * user JWTs against the same issuer as the api — no issuer duplicated in config, no separate secret.
 *
 * <p>Mirrors the api/console loaders: AppRole login (from {@code vault.address/role-id/secret-id}),
 * then reads the shared cross-application {@code <secret-name>/datahub-platform} secret. This is the
 * only Vault this lean service needs — it has no DB, Pulsar, or session store. Registered in
 * {@code DatahubAnalysisApplication.main} (not {@code spring.factories}), so it runs on real startup
 * but not in {@code @SpringBootTest} contexts, which supply a dummy issuer instead.
 */
@Slf4j
public class VaultConfigurationLoader implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();

        String vaultAddress = environment.getProperty("vault.address");
        String roleId = environment.getProperty("vault.role-id");
        String secretId = environment.getProperty("vault.secret-id");
        String secretName = environment.getProperty("vault.secret-name", "intellistream-datahub");

        Map<String, Object> vaultMap = new HashMap<>();
        try {
            var config = new VaultConfig()
                    .address(vaultAddress)
                    .engineVersion(2)
                    .build();
            var bootstrap = Vault.create(config);
            var token = bootstrap.auth().loginByAppRole(roleId, secretId);
            config.token(token.getAuthClientToken());
            Vault vault = Vault.create(config);

            // The Keycloak issuer is the single shared atom between the api (JWT resource server)
            // and this service; it lives in the cross-application "datahub-platform" secret.
            var data = vault.logical().read(secretName + "/datahub-platform").getData();
            vaultMap.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", data.get("keycloak.issuer"));
        } catch (VaultException e) {
            log.error("Failed to load JWT issuer from Vault: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }

        environment.getPropertySources().addLast(new MapPropertySource("vaultProperties", vaultMap));
    }
}
