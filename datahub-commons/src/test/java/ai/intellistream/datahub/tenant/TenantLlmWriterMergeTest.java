// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually reaches Vault.
 *
 * <p>A KV v2 write replaces the whole secret, so this merge decides what survives a save. Both of
 * its rules fail silently: erasing another section, or dropping a credential, produces a valid
 * write that Vault accepts without complaint and that nothing downstream reports. The symptom is a
 * feature that stopped working some time later.
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
    void theCredentialSurvivesWhenTheCallerPassesItThrough() {
        // The service reads the stored key and hands it back when the form did not supply one.
        // This is the last point at which that could go wrong.
        Map<String, String> merged = TenantLlmWriter.merge(
                existing(), section("anthropic", "claude-sonnet-5", "sk-ant-stored"));

        assertThat(merged).containsEntry("llm.api-key", "sk-ant-stored")
                .containsEntry("llm.model", "claude-sonnet-5");
    }

    @Test
    void aKeyTheSectionOmitsIsRemovedRatherThanInherited() {
        // The other half of the same rule: this is how a setting gets unset, and it is exactly why
        // the caller must pass through anything it wants to keep. Nothing here can tell "the user
        // cleared this" from "the caller forgot it".
        Map<String, String> merged = TenantLlmWriter.merge(
                existing(), section("openai-compatible", "qwen3-32b", null));

        assertThat(merged).doesNotContainKey("llm.api-key")
                .doesNotContainKey("llm.turn-timeout");
    }

    @Test
    void anotherSectionIsNeverTouched() {
        Map<String, String> merged = TenantLlmWriter.merge(
                existing(), section("anthropic", "claude-opus-5", "sk-ant-stored"));

        assertThat(merged).containsEntry("billing.plan", "enterprise");
    }

    @Test
    void blankValuesAreOmittedRatherThanStoredAsEmptyStrings() {
        // TenantLlm treats blank as unset everywhere it reads, so storing "" would be a value that
        // means nothing and reads as configured.
        Map<String, String> section = section("anthropic", "claude-opus-5", "sk-ant-stored");
        section.put("instructions", "   ");

        assertThat(TenantLlmWriter.merge(existing(), section)).doesNotContainKey("llm.instructions");
    }

    @Test
    void aFirstConfigurationStartsFromNothing() {
        Map<String, String> merged = TenantLlmWriter.merge(
                Map.of(), section("anthropic", "claude-opus-5", "sk-ant-new"));

        assertThat(merged).containsOnlyKeys("llm.provider", "llm.model", "llm.api-key");
    }
}
