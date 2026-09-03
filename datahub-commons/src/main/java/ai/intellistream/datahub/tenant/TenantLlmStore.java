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
 * Reads the {@code llm.*} section of each tenant's {@code <mount>/tenant-config/<org-name>} secret.
 *
 * <p>Its own secret rather than a block in {@code tenant-resources}, which holds every tenant's
 * database credentials: Vault policies are path-based and KV plugins do not support
 * {@code allowed_parameters}, so a future write path could not be narrowed to model settings if
 * this shared that secret. Nothing writes it yet, and reads need no policy change.
 *
 * <p>Keyed by organization name, matching how {@code tenant-resources} keys its entries — one
 * convention for tenant configuration rather than two, and an operator writing one of these should
 * not have to look a uuid up first. The cost is that renaming an organization strands its config,
 * but a rename already means editing {@code tenant-resources} by hand, so it is the same edit.
 *
 * <p>The secret is {@code tenant-config} rather than {@code tenant-llm} so other per-tenant
 * settings can join it without another secret and another policy line. Sections are separated by a
 * flat key prefix — {@code llm.provider}, {@code llm.api-key} — the same shape the
 * {@code datahub-console} and {@code datahub-platform} secrets already use. A new section picks its
 * own prefix and reads only its own keys, so the sections never collide.
 */
@Slf4j
@Service
public class TenantLlmStore {

    private static final String LLM_PREFIX = "llm.";

    private final JsonMapper jsonMapper;
    private final VaultProperties vault;

    public TenantLlmStore(JsonMapper jsonMapper, VaultProperties vault) {
        this.jsonMapper = jsonMapper;
        this.vault = vault;
    }

    /**
     * The configuration for each named tenant, omitting those with none. Batched so a refresh costs
     * one AppRole login.
     *
     * <p>A Vault failure yields an empty map: every tenant falling back to the deployment default
     * beats a refresh that throws and leaves the whole tenant registry stale.
     *
     * @param orgNames organization names, as {@code tenant-resources} keys them
     * @return the same names, mapped to the configuration each one has
     */
    public Map<String, TenantLlm> readAll(Collection<String> orgNames) {
        if (orgNames == null || orgNames.isEmpty()) {
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
        for (String orgName : orgNames) {
            TenantLlm llm = read(client, orgName);
            if (llm != null) {
                configured.put(orgName, llm);
            }
        }
        return configured;
    }

    /**
     * The {@code llm.*} keys with the prefix stripped, so {@code llm.api-key} becomes the
     * {@code api-key} that {@link TenantLlm} maps. Everything else in the secret belongs to another
     * section and is ignored — which is what lets sections share one secret.
     */
    static Map<String, String> llmSection(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        Map<String, String> section = new HashMap<>();
        data.forEach((key, value) -> {
            if (key.startsWith(LLM_PREFIX)) {
                section.put(key.substring(LLM_PREFIX.length()), value);
            }
        });
        return section;
    }

    /** Null if the tenant has no {@code llm.*} keys, which is the ordinary case, not an error. */
    private TenantLlm read(Vault client, String orgName) {
        String path = vault.secretName() + "/tenant-config/" + orgName;
        try {
            Map<String, String> section = llmSection(client.logical().read(path).getData());
            return section.isEmpty() ? null : jsonMapper.convertValue(section, TenantLlm.class);
        } catch (VaultException e) {
            if (e.getHttpStatusCode() == 404) {
                return null; // never configured, or deleted
            }
            log.warn("Could not read the model configuration for tenant {}: {}", orgName, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // Per tenant, not per pass: one tenant's typo must not cost the others theirs.
            log.warn("Ignoring unusable model configuration for tenant {}: {}", orgName, e.getMessage());
            return null;
        }
    }
}
