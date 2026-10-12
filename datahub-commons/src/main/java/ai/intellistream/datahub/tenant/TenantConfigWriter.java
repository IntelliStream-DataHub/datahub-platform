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
import java.util.function.UnaryOperator;

/**
 * Writes a tenant's {@code <mount>/tenant-config/<org-name>} secret, one section at a time.
 *
 * <p>The counterpart to {@link TenantLlmStore}, and the only thing in the platform that writes to
 * Vault at all. Everything else reads.
 *
 * <h2>Sections</h2>
 * The secret holds several sections told apart by a flat key prefix: {@code llm.*} for the model
 * configuration, {@code flow.*} for the credentials flows use ({@link TenantFlowSection}). A KV v2
 * write replaces the whole secret, so writing one section means read, change, write, and every
 * change carries the keys it does not own across untouched — otherwise saving one section would
 * erase another.
 *
 * <h2>Compare-and-set</h2>
 * That read-modify-write is exactly where two savers lose each other's changes, so the write
 * carries the version the change was based on and Vault rejects it if anything landed in between.
 * A lost update here is somebody's credential silently reverting, which nothing downstream would
 * report — the assistant, or a flow, would simply start failing.
 *
 * <p>Which is why a change is a function of what is stored rather than a map the caller passes in:
 * {@link #update} applies it to the secret as read inside the window compare-and-set guards. A
 * caller holding its own copy would be writing back something read before that window opened. The
 * same reasoning makes {@code keepStoredApiKey} a flag rather than a value.
 *
 * <p>The caller is expected to have checked that this tenant may be written by whoever asked; this
 * class enforces nothing about identity. It holds the platform's own Vault credential, so it must
 * never be reachable from a request path that has not made that check.
 */
@Slf4j
@Service
public class TenantConfigWriter {

    private static final String LLM_PREFIX = "llm.";
    private static final String API_KEY = "api-key";

    private final VaultProperties vault;

    public TenantConfigWriter(VaultProperties vault) {
        this.vault = vault;
    }

    /**
     * Replaces this tenant's {@code llm.*} keys with {@code section}, leaving every other key in
     * the secret as it was.
     *
     * @param orgName organization name, as {@code tenant-resources} keys it
     * @param section the new section, unprefixed — {@code provider}, {@code api-key}, and so on
     * @param keepStoredApiKey carry the stored credential across rather than taking one from
     *                         {@code section}, for a save that did not supply one
     * @throws IllegalStateException if Vault is unreachable, refuses, or the secret changed while
     *                               this change was being prepared
     */
    public void writeLlmSection(String orgName, Map<String, String> section, boolean keepStoredApiKey) {
        Map<String, String> written = update(orgName,
                existing -> merge(existing, section, keepStoredApiKey));
        long llmKeys = written.keySet().stream().filter(key -> key.startsWith(LLM_PREFIX)).count();
        log.info("Model configuration updated for tenant {} ({} llm keys)", orgName, llmKeys);
    }

    /**
     * What this tenant's secret holds now, read from Vault rather than from any cache, so that a
     * section reads back as it was just written. Empty when there is no secret.
     *
     * @throws IllegalStateException if Vault is unreachable or refuses
     */
    public Map<String, String> current(String orgName) {
        Vault client = VaultClientFactory.login(vault);
        return Map.copyOf(readExisting(client, pathOf(orgName)).data());
    }

    /**
     * Applies {@code change} to the secret as it stands and writes the result under
     * compare-and-set. A change that leaves the secret as it was writes nothing, so removing an
     * entry that is not there adds no version.
     *
     * @param orgName organization name, as {@code tenant-resources} keys it
     * @param change  receives every key in the secret and returns every key the secret should hold,
     *                so it must carry the keys of other sections across
     * @return what the secret holds after the write
     * @throws IllegalStateException if Vault is unreachable, refuses, or the secret changed while
     *                               this change was being prepared
     */
    public Map<String, String> update(String orgName, UnaryOperator<Map<String, String>> change) {
        Vault client = VaultClientFactory.login(vault);
        String path = pathOf(orgName);

        Existing existing = readExisting(client, path);
        Map<String, String> changed = Map.copyOf(change.apply(existing.data()));
        if (changed.equals(existing.data())) {
            return changed;
        }

        int status;
        try {
            WriteOptions options = new WriteOptions();
            if (existing.version() != null) {
                options = options.checkAndSet(existing.version());
            }
            status = client.logical()
                    .write(path, Map.<String, Object>copyOf(changed), null, options.build())
                    .getRestResponse().getStatus();
        } catch (VaultException e) {
            throw new IllegalStateException("Could not write " + path + ": " + e.getMessage(), e);
        }
        // The driver returns a 4xx as a response rather than throwing, so a refused write looks
        // like a successful one unless the status is checked here.
        if (status == 403) {
            throw new IllegalStateException("Vault refused the write to " + path
                    + ". The AppRole policy needs create and update on this path"
                    + " (KV v2 writes go to <mount>/data/...).");
        }
        if (status == 400) {
            // Vault answers 400, not 409, when a check-and-set fails. Saying "conflict" would be a
            // guess — a genuinely malformed write lands here too — so name both.
            throw new IllegalStateException("Vault rejected the write to " + path
                    + ", which usually means someone else changed these settings first."
                    + " Reload and try again.");
        }
        if (status != 200 && status != 204) {
            throw new IllegalStateException("Could not write " + path + ": Vault answered HTTP "
                    + status);
        }
        return changed;
    }

    /**
     * The secret to write: every key outside the {@code llm.} prefix as it was, plus this section
     * under the prefix.
     *
     * <p>Package-private and static so the one part of this class with rules of its own can be
     * tested without a Vault. Three of those rules are load-bearing. Keys outside the prefix are
     * carried across untouched, or another section arriving later would be erased by someone saving
     * their model settings. A prefixed key the section does not carry is dropped rather than kept,
     * so removing a setting removes it, which is why the caller must pass through any value it
     * means to preserve. And the credential is the one exception to that: under
     * {@code keepStoredApiKey} it is taken from {@code existing}, so a save that did not supply one
     * preserves what the secret holds now rather than whatever the caller last saw.
     */
    static Map<String, String> merge(Map<String, String> existing, Map<String, String> section,
                                     boolean keepStoredApiKey) {
        Map<String, String> merged = new LinkedHashMap<>();
        existing.forEach((key, value) -> {
            if (!key.startsWith(LLM_PREFIX)) {
                merged.put(key, value);
            }
        });
        if (keepStoredApiKey) {
            String stored = existing.get(LLM_PREFIX + API_KEY);
            if (stored != null && !stored.isBlank()) {
                merged.put(LLM_PREFIX + API_KEY, stored);
            }
        }
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
    private Existing readExisting(Vault client, String path) {
        try {
            LogicalResponse response = client.logical().read(path);
            int status = response.getRestResponse().getStatus();
            if (status == 404) {
                return new Existing(Map.of(), 0L);
            }
            if (status != 200) {
                // A 403 must not pass for "no secret": that would write with cas=0 and fail with a
                // misleading conflict message, or overwrite under a policy that allows only create.
                throw new IllegalStateException("Could not read " + path + ":"
                        + " Vault answered HTTP " + status
                        + (status == 403 ? ". The AppRole policy needs read on this path." : ""));
            }
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
            throw new IllegalStateException("Could not read " + path + ": "
                    + e.getMessage(), e);
        }
    }

    private String pathOf(String orgName) {
        return vault.secretName() + "/tenant-config/" + orgName;
    }

    /** @param version null when the secret exists but its version is unknown — see {@link #readExisting} */
    private record Existing(Map<String, String> data, Long version) {
    }
}
