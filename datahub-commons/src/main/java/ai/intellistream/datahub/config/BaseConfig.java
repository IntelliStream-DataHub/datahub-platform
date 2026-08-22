// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class BaseConfig {

    /**
     * The resolved {@code vault.*} settings for beans that talk to Vault at runtime (the tenant
     * registry refresh). The startup loader resolves the same record from the environment before
     * the context exists; see {@link VaultConfigurationLoader}.
     */
    @Bean
    VaultProperties vaultProperties(Environment environment) {
        return VaultProperties.from(environment);
    }
}
