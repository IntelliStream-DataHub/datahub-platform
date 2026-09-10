// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually reaches Vault.
 *
 * <p>A KV v2 write replaces the whole secret, so this merge decides what survives a save. Each of
 * its rules fails silently: erasing another section, dropping a credential, or reverting one to an
 * older value, produces a valid write that Vault accepts without complaint and that nothing
 * downstream reports. The symptom is a feature that stopped working some time later.
 */
class TenantLlmWriterMergeTest {

    private static Map<String, String> existing() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("llm.provider", "anthropic");
        data.put("llm.model", "claude-opus-5");
        data.put("llm.api-key", "sk-ant-stored");
        data.put("llm.turn-timeout", "10m");
        // A section belonging to something else. Nothing writes one yet; the point is that this
        // writer stops being safe the moment something does.
        data.put("billing.plan", "enterprise");
        return data;
    }

    private static Map<String, String> section(String provider, String model, String apiKey) {
        Map<String, String> section = new LinkedHashMap<>();
        section.put("provider", provider);
        section.put("model", model);
        section.put("api-key", apiKey);
        return section;
    }

    @Test
    void aSuppliedCredentialReplacesTheStoredOne() {
        Map<String, String> merged = TenantLlmWriter.merge(
                existing(), section("anthropic", "claude-sonnet-5", "sk-ant-new"), false);

        assertThat(merged).containsEntry("llm.api-key", "sk-ant-new")
                .containsEntry("llm.model", "claude-sonnet-5");
    }

    /**
     * The credential is taken from the secret being replaced, not from the caller.
     *
     * <p>The caller's copy comes from a tenant cache up to five minutes old, so passing it through
     * would write a key rotated since back to what it was. Reading it here puts it inside the
     * window compare-and-set guards, which is the only place it is current.
     */
    @Test
    void theKeptCredentialComesFromTheSecretRatherThanTheSection() {
        Map<String, String> merged = TenantLlmWriter.merge(
                existing(), section("anthropic", "claude-sonnet-5", null), true);

        assertThat(merged).containsEntry("llm.api-key", "sk-ant-stored")
                .containsEntry("llm.model", "claude-sonnet-5");
    }

    @Test
    void keepingACredentialThatIsNotThereStoresNothing() {
        Map<String, String> existing = new LinkedHashMap<>();
        existing.put("llm.provider", "openai-compatible");

        Map<String, String> merged = TenantLlmWriter.merge(
                existing, section("openai-compatible", "qwen3-32b", null), true);

        assertThat(merged).doesNotContainKey("llm.api-key");
    }

    @Test
    void aKeyTheSectionOmitsIsRemovedRatherThanInherited() {
        // The other half of the same rule: this is how a setting gets unset, and it is exactly why
        // the caller must pass through anything it wants to keep. Nothing here can tell "the user
        // cleared this" from "the caller forgot it".
        Map<String, String> merged = TenantLlmWriter.merge(
                existing(), section("openai-compatible", "qwen3-32b", null), false);

        assertThat(merged).doesNotContainKey("llm.api-key")
                .doesNotContainKey("llm.turn-timeout");
    }

    @Test
    void anotherSectionIsNeverTouched() {
        Map<String, String> merged = TenantLlmWriter.merge(
                existing(), section("anthropic", "claude-opus-5", "sk-ant-stored"), false);

        assertThat(merged).containsEntry("billing.plan", "enterprise");
    }

    @Test
    void blankValuesAreOmittedRatherThanStoredAsEmptyStrings() {
        // TenantLlm treats blank as unset everywhere it reads, so storing "" would be a value that
        // means nothing and reads as configured.
        Map<String, String> section = section("anthropic", "claude-opus-5", "sk-ant-stored");
        section.put("instructions", "   ");

        assertThat(TenantLlmWriter.merge(existing(), section, false))
                .doesNotContainKey("llm.instructions");
    }

    @Test
    void aFirstConfigurationStartsFromNothing() {
        Map<String, String> merged = TenantLlmWriter.merge(
                Map.of(), section("anthropic", "claude-opus-5", "sk-ant-new"), false);

        assertThat(merged).containsOnlyKeys("llm.provider", "llm.model", "llm.api-key");
    }
}
