// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.VaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class VaultConfigurationLoader implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String KEYCLOAK_HOOK_CLASS = "ai.intellistream.datahub.config.VaultKeycloakData";

    private String secretName;

    public VaultConfigurationLoader() {
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();

        String vaultAddress = environment.getProperty("vault.address");
        String roleId = environment.getProperty("vault.role-id");
        String secretId = environment.getProperty("vault.secret-id");
        this.secretName = environment.getProperty("vault.secret-name", "intellistream-datahub");

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

            addPulsarData(vault, vaultMap);
            loadKeycloakIfPresent(vault, vaultMap);

        } catch (VaultException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        MapPropertySource propertySource = new MapPropertySource("vaultProperties", vaultMap);
        environment.getPropertySources().addLast(propertySource);
    }

    /**
     * Optional hook: services that need Keycloak/JWT vault data place a
     * {@code VaultKeycloakData} class with a static
     * {@code load(Vault, Map<String, Object>, String secretName)} method on the
     * classpath. Services that don't need it (e.g. headless consumers) simply
     * don't ship the class and the loader skips silently. Real failures inside
     * the hook still propagate — only ClassNotFoundException is swallowed.
     */
    private void loadKeycloakIfPresent(Vault vault, Map<String, Object> vaultMap) {
        try {
            Class<?> hook = Class.forName(KEYCLOAK_HOOK_CLASS);
            var method = hook.getMethod("load", Vault.class, Map.class, String.class);
            method.invoke(null, vault, vaultMap, this.secretName);
        } catch (ClassNotFoundException e) {
            log.debug("VaultKeycloakData not on classpath; skipping keycloak vault load");
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("VaultKeycloakData is present but its signature is invalid", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("VaultKeycloakData failed to load keycloak vault data", cause);
        }
    }

    private void addPulsarData(Vault vault, Map<String, Object> vaultMap) throws VaultException {
        // Shared cross-application config lives in the "datahub-platform" secret, with each
        // field namespaced by subsystem (pulsar.*). Pulsar is global today; the per-tenant
        // pulsar block in the tenant registry stays unread until multi-cluster work lands.
        var vaultData = vault.logical().read(this.secretName + "/datahub-platform").getData();
        String clientId = vaultData.get("pulsar.client-id");
        String clientSecret = vaultData.get("pulsar.client-secret");
        String adminClientId = vaultData.get("pulsar.admin-client-id");
        String adminClientSecret = vaultData.get("pulsar.admin-client-secret");
        String host = vaultData.get("pulsar.host");
        String issuerUrl = vaultData.get("pulsar.issuer-url");
        String internalTenant = vaultData.get("pulsar.internal-tenant");

        vaultMap.put("pulsar.client-id", clientId);
        vaultMap.put("pulsar.admin-client-id", adminClientId);
        vaultMap.put("pulsar.admin-client-secret", adminClientSecret);
        vaultMap.put("pulsar.client-secret", clientSecret);
        vaultMap.put("pulsar.host", host);
        vaultMap.put("pulsar.issuer-url", issuerUrl);
        vaultMap.put("pulsar.internal-tenant", internalTenant);
        vaultMap.put("pulsar.service.url", "pulsar+ssl://"+host+":6651");
        vaultMap.put("pulsar.service.httpUrl", "https://"+host+":8443");
    }
}
