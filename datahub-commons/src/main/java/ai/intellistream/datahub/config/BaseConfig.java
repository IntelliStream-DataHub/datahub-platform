// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.VaultException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BaseConfig {

    @Value("${vault.address}")
    private String vaultAddress;

    @Value("${vault.role-id}")
    private String roleId;

    @Value("${vault.secret-id}")
    private String secretId;

    @Bean(name = "vault")
    Vault vault() throws VaultException {
        var config = new VaultConfig()
                .address(this.vaultAddress)
                .build();
        var v = Vault.create(config);
        var t = v.auth().loginByAppRole(roleId, secretId);
        config.token(t.getAuthClientToken());
        return Vault.create(config);
    }
}
