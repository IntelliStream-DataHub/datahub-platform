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
        Map<String, String> merged = merge(existing.data(), section);

        try {
            WriteOptions options = new WriteOptions();
            if (existing.version() != null) {
                options = options.checkAndSet(existing.version());
            }
            client.logical().write(path, Map.copyOf(merged), null, options.build());
        } catch (VaultException e) {
            if (e.getHttpStatusCode() == 403) {
                throw new IllegalStateException("Vault refused the write to " + path
                        + ". The AppRole policy needs create and update on this path"
                        + " (KV v2 writes go to <mount>/data/...). (" + e.getMessage() + ")", e);
            }
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
     * The secret to write: every key outside the {@code llm.} prefix as it was, plus this section
     * under the prefix.
     *
     * <p>Package-private and static so the one part of this class with rules of its own can be
     * tested without a Vault. Two of those rules are load-bearing. Keys outside the prefix are
     * carried across untouched, or another section arriving later would be erased by someone saving
     * their model settings. And a prefixed key the section does not carry is dropped rather than
     * kept, so removing a setting removes it — which is also why the caller must pass through any
     * value it means to preserve, the credential included.
     */
    static Map<String, String> merge(Map<String, String> existing, Map<String, String> section) {
        Map<String, String> merged = new LinkedHashMap<>();
        existing.forEach((key, value) -> {
            if (!key.startsWith(LLM_PREFIX)) {
                merged.put(key, value);
            }
        });
        section.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                merged.put(LLM_PREFIX + key, value);
            }
        });
        return merged;
    }

    /**
     * The secret as it stands, and the version to check against.
     *
     * <p>Three states, and conflating any two of them breaks the write:
     *
     * <ul>
     *   <li><b>0</b> — no secret here. In KV v2 {@code cas=0} means "only if this key does not
     *       exist", which is exactly the check a first-time write wants: if another writer creates
     *       it in between, this one fails rather than overwriting them.</li>
     *   <li><b>a version</b> — the secret exists and this is what the merge was based on.</li>
     *   <li><b>null</b> — it exists but the response carried no version. Then there is no check to
     *       make, and the write goes without one. It must <em>not</em> fall back to 0, which asserts
     *       the secret does not exist and so fails every single time against one that does.</li>
     * </ul>
     */
    private Existing read(Vault client, String path) {
        try {
            LogicalResponse response = client.logical().read(path);
            Map<String, String> data = response.getData();
            if (data == null || data.isEmpty()) {
                // Vault answers 200 with an empty body for a soft-deleted secret, so this is
                // "nothing here" rather than "unreadable".
                return new Existing(Map.of(), 0L);
            }
            var metadata = response.getDataMetadata();
            Long version = metadata == null ? null : metadata.getVersion();
            if (version == null) {
                log.warn("No version in the Vault response for {}; writing without check-and-set,"
                        + " so a concurrent edit could be overwritten", path);
            }
            return new Existing(data, version);
        } catch (VaultException e) {
            if (e.getHttpStatusCode() == 404) {
                return new Existing(Map.of(), 0L);
            }
            throw new IllegalStateException("Could not read " + path + " before writing it: "
                    + e.getMessage(), e);
        }
    }

    /** @param version null when the secret exists but its version is unknown — see {@link #read} */
    private record Existing(Map<String, String> data, Long version) {
    }
}
