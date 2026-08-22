// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads an application's configuration from Vault before the Spring context exists, so that
 * Vault-backed values (Pulsar credentials, the Keycloak issuer, the console's OAuth2 client and
 * session store, ...) are ordinary properties by the time beans bind to them.
 *
 * <p>Runs on {@link ApplicationEnvironmentPreparedEvent}: resolves {@link VaultProperties} from the
 * environment (application files or {@code VAULT_*} variables), logs in once through
 * {@link VaultClientFactory}, which applies the optional mTLS keystore, then lets each
 * {@link VaultSecretContributor} read what its application needs. The result is registered with
 * {@code addLast}, i.e. lowest precedence, so a value in an application file or the environment
 * still overrides Vault; several modules rely on that (plaintext Pulsar overrides in local dev, the
 * console's chat defaults).
 *
 * <p>Register it in {@code main} with {@code app.addListeners(...)} rather than in
 * {@code spring.factories} unless a {@code @SpringBootTest} genuinely needs Vault: listeners added
 * in {@code main} never run in test contexts, which is what keeps those hermetic.
 */
@Slf4j
public class VaultConfigurationLoader implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    public static final String PROPERTY_SOURCE_NAME = "vaultProperties";

    private final List<VaultSecretContributor> contributors;

    public VaultConfigurationLoader(VaultSecretContributor... contributors) {
        this.contributors = List.of(contributors);
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        VaultProperties properties = VaultProperties.from(environment);

        Map<String, Object> vaultMap = new HashMap<>();
        Vault vault = VaultClientFactory.login(properties);
        try {
            for (VaultSecretContributor contributor : contributors) {
                contributor.contribute(vault, properties, vaultMap);
            }
        } catch (VaultException e) {
            log.error("Failed to load configuration from Vault at {}: {}", properties.address(), e.getMessage(), e);
            throw new IllegalStateException("Failed to load configuration from Vault at " + properties.address(), e);
        }

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, vaultMap));
    }
}
