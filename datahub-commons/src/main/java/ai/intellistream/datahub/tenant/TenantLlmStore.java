// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import ai.intellistream.datahub.config.VaultClientFactory;
import ai.intellistream.datahub.config.VaultProperties;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads each tenant's model configuration from {@code <mount>/tenant-llm/<org-id>}.
 *
 * <h3>Why not a block inside {@code tenant-resources}</h3>
 * Because this is the one piece of tenant configuration a person will eventually edit, and
 * {@code tenant-resources} is a single secret holding every tenant's database credentials. Vault
 * cannot narrow a write within a secret — ACL policies are path-based, and KV plugins do not
 * support the {@code allowed_parameters} family at all — so "may update
 * {@code tenant-resources.acme.llm} and nothing else" is not expressible however it is phrased.
 * Its own path is the only shape in which Vault could ever enforce that boundary.
 *
 * <p>Nothing writes it yet: today it is placed by an operator, and reads need no policy change
 * because the AppRole already reads the whole mount. Putting it in the right place now means the
 * write path is a policy line rather than a data migration for everyone who configured it early.
 *
 * <h3>Keyed by org id, not org name</h3>
 * {@code tenant-resources} is keyed by organization <em>name</em>, which only this package ever
 * sees. Every request instead carries an org id — {@code TenantContext}, {@code UserSession} — so
 * keying on that means renaming an organization does not strand its configuration.
 */
@Slf4j
@Service
public class TenantLlmStore {

    private final JsonMapper jsonMapper;
    private final VaultProperties vault;

    public TenantLlmStore(JsonMapper jsonMapper, VaultProperties vault) {
        this.jsonMapper = jsonMapper;
        this.vault = vault;
    }

    /**
     * The model configuration for each of these tenants, omitting those that have none.
     *
     * <p>A batch rather than one call per tenant so the whole refresh costs a single AppRole login.
     * {@link VaultClientFactory#login} hands back a client that is already authenticated and set to
     * KV v2, so the token, the mutual-TLS settings and the {@code data.data} unwrapping are all
     * somebody else's problem — the same client every {@code VaultSecretContributor} uses.
     *
     * <p>A Vault failure yields an empty map rather than an exception. Every tenant then falls back
     * to the deployment default, which is a far better outcome than a refresh that throws and
     * leaves the whole tenant registry stale.
     */
    public Map<String, TenantLlm> readAll(Collection<String> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return Map.of();
        }
        Vault client;
        try {
            client = VaultClientFactory.login(vault);
        } catch (RuntimeException e) {
            log.warn("Could not log in to Vault to read model configuration: {}", e.getMessage());
            return Map.of();
        }

        Map<String, TenantLlm> configured = new HashMap<>();
        for (String orgId : orgIds) {
            TenantLlm llm = read(client, orgId);
            if (llm != null) {
                configured.put(orgId, llm);
            }
        }
        return configured;
    }

    /**
     * One tenant's configuration, or null if it has none.
     *
     * <p>Absent is the ordinary case rather than an error: a tenant without its own model uses the
     * deployment default, which is what every tenant did before this existed.
     */
    private TenantLlm read(Vault client, String orgId) {
        String path = vault.secretName() + "/tenant-llm/" + orgId;
        try {
            Map<String, String> data = client.logical().read(path).getData();
            return data == null || data.isEmpty() ? null : jsonMapper.convertValue(data, TenantLlm.class);
        } catch (VaultException e) {
            if (e.getHttpStatusCode() == 404) {
                return null; // never configured, or deleted
            }
            log.warn("Could not read the model configuration for tenant {}: {}", orgId, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // A malformed secret — an unparseable provider, say. One tenant's typo must not cost
            // every other tenant its configuration, so this is caught per tenant, not per pass.
            log.warn("Ignoring unusable model configuration for tenant {}: {}", orgId, e.getMessage());
            return null;
        }
    }
}
