// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import ai.intellistream.datahub.config.VaultClientFactory;
import ai.intellistream.datahub.config.VaultProperties;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code llm.*} section of each tenant's {@code <mount>/tenant-config/<org-name>} secret.
 *
 * <p>Its own secret rather than a block in {@code tenant-resources}, and the split is by who may
 * write it. {@code tenant-resources} is what the operator provisions and a customer must not touch
 * — every tenant's database credentials, and the {@code tenant-config} block of feature
 * entitlements saying what that tenant has been given. This secret is the opposite: the customer's
 * own settings, which it should eventually edit for itself from the console.
 *
 * <p>A secret per tenant rather than one holding them all, though not for isolation as things
 * stand: there is a single AppRole with read on the whole mount and nothing writes these yet. It is
 * about what writing them will look like. One shared secret makes every write a read-modify-write
 * of the whole blob, so concurrent edits lose each other without careful {@code cas} and one bug
 * reaches every tenant's credentials; separate secrets cannot interact. Splitting later, after
 * customers have written into it, would be a data migration.
 *
 * <p>It also leaves room for Vault to enforce the split, since a policy can name one path — but
 * that is not assumed. The expectation is that the console checks the caller's
 * {@code settings/write} organization group and writes with the platform's own credential, which
 * makes the group check the boundary and not this path.
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
        List<String> withoutAModel = new ArrayList<>();
        int usable = 0;
        for (String orgName : orgNames) {
            TenantLlm llm = read(client, orgName);
            if (llm == null) {
                withoutAModel.add(orgName);
                continue;
            }
            configured.put(orgName, llm);
            if (llm.isUsable()) {
                usable++;
            } else {
                // Written but incomplete, which is worth separating from absent: whoever wrote it
                // believes they configured something.
                withoutAModel.add(orgName + " (incomplete)");
            }
        }
        // Says outright why a tenant has no assistant. Without it the only symptom is a panel that
        // does not render, and nothing tells an unwritten secret from a misspelled one.
        log.info("Model configuration: {} of {} tenants have a usable one{}", usable, orgNames.size(),
                withoutAModel.isEmpty() ? "" : ", without: " + String.join(", ", withoutAModel));
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

    /** Null if the tenant has no {@code llm.*} keys, which is not an error — it has no assistant. */
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
