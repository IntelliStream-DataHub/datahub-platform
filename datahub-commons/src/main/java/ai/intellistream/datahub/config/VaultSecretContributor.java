// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;

import java.util.Map;

/**
 * One application's share of the Vault-backed configuration. {@link VaultConfigurationLoader}
 * logs in once and hands the authenticated client to each contributor, which reads the secrets it
 * needs and puts the resulting Spring properties into {@code out}.
 */
@FunctionalInterface
public interface VaultSecretContributor {

    void contribute(Vault vault, VaultProperties properties, Map<String, Object> out)
            throws VaultException;
}
