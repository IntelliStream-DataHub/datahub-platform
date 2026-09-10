// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pulling the {@code llm.*} section out of a tenant's {@code tenant-config} secret.
 *
 * <p>This is what lets several kinds of setting share one secret, so it is worth pinning: a reader
 * that took keys outside its own prefix would break the moment a second section existed.
 */
class TenantLlmSectionTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private TenantLlm parse(Map<String, String> secret) {
        Map<String, String> section = TenantLlmStore.llmSection(secret);
        return section.isEmpty() ? null : json.convertValue(section, TenantLlm.class);
    }

    @Test
    void stripsThePrefixSoTheFieldNamesMatch() {
        TenantLlm llm = parse(Map.of(
                "llm.provider", "anthropic",
                "llm.api-key", "sk-ant-x",
                "llm.model", "claude-opus-5",
                "llm.turn-timeout", "10m"));

        assertThat(llm.getProvider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(llm.getApiKey()).isEqualTo("sk-ant-x");
        assertThat(llm.getModel()).isEqualTo("claude-opus-5");
        assertThat(llm.getTurnTimeoutDuration()).isEqualTo(java.time.Duration.ofMinutes(10));
    }

    @Test
    void ignoresEverythingOutsideTheSection() {
        // The whole point of the prefix: a future section shares this secret and neither reader
        // sees the other's keys.
        TenantLlm llm = parse(Map.of(
                "llm.model", "claude-opus-5",
                "retention.events-days", "90",
                "branding.logo-url", "https://acme.example/logo.png"));

        assertThat(llm.getModel()).isEqualTo("claude-opus-5");
        assertThat(llm.getApiKey()).isNull();
    }

    @Test
    void aSecretWithNoLlmKeysHasNoModelConfiguration() {
        // Not an error: that tenant uses the deployment default.
        assertThat(parse(Map.of("retention.events-days", "90"))).isNull();
        assertThat(parse(Map.of())).isNull();
        assertThat(TenantLlmStore.llmSection(null)).isEmpty();
    }

    @Test
    void aKeyThatMerelyStartsWithLlmIsNotInTheSection() {
        // "llmx.model" is another section's key, not a malformed one of ours. The separator is
        // part of the prefix precisely so this cannot be confused.
        assertThat(TenantLlmStore.llmSection(Map.of("llmx.model", "nope"))).isEmpty();
    }
}
