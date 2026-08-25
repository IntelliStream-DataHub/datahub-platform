// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

/**
 * The console's Vault loader: the shared one with {@link ConsoleVaultSecrets} plugged in.
 *
 * <p>Registered in {@code META-INF/spring.factories}, unlike the other applications which add the
 * loader in {@code main}, because the console's {@code @SpringBootTest} integration tests
 * ({@code PolicyApiIT}) run against a live api and need the Vault-provided OAuth2 client and
 * {@code datahub.url}. Do not also add it in {@code main}: that would log in to Vault twice.
 */
public class VaultConfigurationLoader
        extends ai.intellistream.datahub.config.VaultConfigurationLoader {

    public VaultConfigurationLoader() {
        super(new ConsoleVaultSecrets());
    }
}
