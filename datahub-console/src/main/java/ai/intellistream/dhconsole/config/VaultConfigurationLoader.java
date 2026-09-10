// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.config.MetricsTlsVaultSecrets;

/**
 * The console's Vault loader: the shared one with {@link ConsoleVaultSecrets} plugged in.
 *
 * <p>Registered in {@code META-INF/spring.factories}, unlike the other applications which add the
 * loader in {@code main}, so it also runs at environment-prepared time under
 * {@code @SpringBootTest} — a Spring test of the console gets the Vault-provided OAuth2 client
 * and {@code datahub.url} without arranging the loader itself. Do not also add it in
 * {@code main}: that would log in to Vault twice.
 */
public class VaultConfigurationLoader
        extends ai.intellistream.datahub.config.VaultConfigurationLoader {

    public VaultConfigurationLoader() {
        super(new ConsoleVaultSecrets(),
                new MetricsTlsVaultSecrets(MetricsTlsVaultSecrets.MANAGEMENT_SSL));
    }
}
