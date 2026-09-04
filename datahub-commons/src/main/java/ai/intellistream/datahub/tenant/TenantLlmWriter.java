// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import ai.intellistream.datahub.config.VaultClientFactory;
import ai.intellistream.datahub.config.VaultProperties;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;
import io.github.jopenlibs.vault.api.WriteOptions;
import io.github.jopenlibs.vault.response.LogicalResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the {@code llm.*} section of a tenant's {@code <mount>/tenant-config/<org-name>} secret.
 *
 * <p>The counterpart to {@link TenantLlmStore}, and the first thing in the platform that writes to
 * Vault at all. Everything else reads.
 *
 * <h2>Only this section</h2>
 * A KV v2 write replaces the whole secret, so writing one section means read, merge, write. Keys
 * outside the {@code llm.} prefix are carried across untouched: another section arriving later must
 * not be erased by someone saving their model settings.
 *
 * <h2>Compare-and-set</h2>
 * That read-modify-write is exactly where two savers lose each other's changes, so the write
 * carries the version the merge was based on and Vault rejects it if anything landed in between.
 * A lost update here is somebody's credential silently reverting, which nothing downstream would
 * report — the assistant would simply start failing.
 *
 * <p>The caller is expected to have checked that this tenant may be written by whoever asked; this
 * class enforces nothing about identity. It holds the platform's own Vault credential, so it must
 * never be reachable from a request path that has not made that check.
 */
@Slf4j
@Service
public class TenantLlmWriter {

    private static final String LLM_PREFIX = "llm.";

    private final VaultProperties vault;

    public TenantLlmWriter(VaultProperties vault) {
        this.vault = vault;
    }

    /**
     * Replaces this tenant's {@code llm.*} keys with {@code section}, leaving every other key in
     * the secret as it was.
     *
     * @param orgName organization name, as {@code tenant-resources} keys it
     * @param section the new section, unprefixed — {@code provider}, {@code api-key}, and so on
     * @throws IllegalStateException if Vault is unreachable, refuses, or the secret changed while
     *                               this change was being prepared
     */
    public void writeLlmSection(String orgName, Map<String, String> section) {
        Vault client = VaultClientFactory.login(vault);
        String path = vault.secretName() + "/tenant-config/" + orgName;

        Existing existing = read(client, path);
        Map<String, String> merged = new LinkedHashMap<>();
        existing.data().forEach((key, value) -> {
            if (!key.startsWith(LLM_PREFIX)) {
                merged.put(key, value);
            }
        });
        section.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                merged.put(LLM_PREFIX + key, value);
            }
        });

        try {
            client.logical().write(path, Map.copyOf(merged), null,
                    new WriteOptions().checkAndSet(existing.version()).build());
        } catch (VaultException e) {
            if (e.getHttpStatusCode() == 400) {
                // Vault answers 400, not 409, when a check-and-set fails. Saying "conflict" would
                // be a guess — a genuinely malformed write lands here too — so name both.
                throw new IllegalStateException("Vault rejected the write to " + path
                        + ", which usually means someone else changed these settings first."
                        + " Reload and try again. (" + e.getMessage() + ")", e);
            }
            throw new IllegalStateException("Could not write " + path + ": " + e.getMessage(), e);
        }
        log.info("Model configuration updated for tenant {} ({} llm keys)", orgName, section.size());
    }

    /**
     * The secret as it stands, and the version to check against.
     *
     * <p>Version 0 is Vault's "must not exist yet", which is the right check for a tenant being
     * configured for the first time: if another writer creates it in between, this one fails
     * rather than overwriting them.
     */
    private Existing read(Vault client, String path) {
        try {
            LogicalResponse response = client.logical().read(path);
            Map<String, String> data = response.getData();
            var metadata = response.getDataMetadata();
            long version = metadata == null || metadata.getVersion() == null ? 0L : metadata.getVersion();
            return new Existing(data == null ? Map.of() : data, version);
        } catch (VaultException e) {
            if (e.getHttpStatusCode() == 404) {
                return new Existing(Map.of(), 0L);
            }
            throw new IllegalStateException("Could not read " + path + " before writing it: "
                    + e.getMessage(), e);
        }
    }

    private record Existing(Map<String, String> data, long version) {
    }
}
