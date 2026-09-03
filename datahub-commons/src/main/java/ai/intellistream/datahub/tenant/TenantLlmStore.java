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
 * <p>Its own secret rather than a block in {@code tenant-resources}, which holds every tenant's
 * database credentials: Vault policies are path-based and KV plugins do not support
 * {@code allowed_parameters}, so a future write path could not be narrowed to model settings if
 * this shared that secret. Nothing writes it yet, and reads need no policy change.
 *
 * <p>Keyed by org id because that is what a request carries; the name is a {@code tenant-resources}
 * map key nobody outside this package sees.
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
     * The configuration for each of these tenants, omitting those with none. Batched so a refresh
     * costs one AppRole login.
     *
     * <p>A Vault failure yields an empty map: every tenant falling back to the deployment default
     * beats a refresh that throws and leaves the whole tenant registry stale.
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

    /** Null if the tenant has none, which is the ordinary case rather than an error. */
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
            // Per tenant, not per pass: one tenant's typo must not cost the others theirs.
            log.warn("Ignoring unusable model configuration for tenant {}: {}", orgId, e.getMessage());
            return null;
        }
    }
}
