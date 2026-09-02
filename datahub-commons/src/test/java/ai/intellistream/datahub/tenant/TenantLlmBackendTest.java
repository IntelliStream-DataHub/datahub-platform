// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code llm-backends} half of a tenant's Vault entry.
 *
 * <p>Deserialization is the whole contract here: these values are hand-written into Vault by an
 * operator, so every reasonable spelling has to work and an unreasonable one has to be loud.
 */
class TenantLlmBackendTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private Tenant tenant(String body) {
        return json.readValue(body, Tenant.class);
    }

    @Test
    void readsANamedBackendFromTheTenantBlock() {
        Tenant tenant = tenant("""
                {"org-id": "t1", "llm-backends": {
                   "house": {"provider": "anthropic", "api-key": "sk-ant-x", "model": "claude-opus-5"}
                }}
                """);

        TenantLlmBackend house = tenant.getLlmBackends().get("house");

        assertThat(house.getProvider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(house.getApiKey()).isEqualTo("sk-ant-x");
        assertThat(house.getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    void readsSeveralBackendsSoOneTenantCanRunBothHostedAndSelfHosted() {
        Tenant tenant = tenant("""
                {"org-id": "t1", "llm-backends": {
                   "house":  {"provider": "anthropic", "api-key": "sk-ant-x", "model": "claude-opus-5"},
                   "onprem": {"provider": "openai-compatible", "base-url": "http://vllm:8000/v1",
                              "model": "qwen3-32b", "reasoning-effort": "none"}
                }}
                """);

        assertThat(tenant.getLlmBackends()).containsOnlyKeys("house", "onprem");
        TenantLlmBackend onprem = tenant.getLlmBackends().get("onprem");
        assertThat(onprem.getProvider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(onprem.getBaseUrl()).isEqualTo("http://vllm:8000/v1");
        assertThat(onprem.getReasoningEffort()).isEqualTo("none");
        // No key: the airgapped path does not need one.
        assertThat(onprem.getApiKey()).isNull();
    }

    @Test
    void aTenantWithNoBlockGetsAnEmptyMapRatherThanNull() {
        assertThat(tenant("""
                {"org-id": "t1"}
                """).getLlmBackends()).isEmpty();
    }

    @Test
    void unsetFieldsStayNullSoTheDeploymentDefaultCanFillThem() {
        TenantLlmBackend backend = tenant("""
                {"org-id": "t1", "llm-backends": {"partial": {"model": "claude-opus-5"}}}
                """).getLlmBackends().get("partial");

        assertThat(backend.getModel()).isEqualTo("claude-opus-5");
        assertThat(backend.getProvider()).isNull();
        assertThat(backend.getApiKey()).isNull();
        assertThat(backend.getBaseUrl()).isNull();
        assertThat(backend.getReasoningEffort()).isNull();
        assertThat(backend.getTurnTimeoutDuration()).isNull();
    }

    @Test
    void providerNameIsCaseAndHyphenInsensitive() {
        assertThat(LlmProvider.parse("openai-compatible")).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(LlmProvider.parse("OPENAI_COMPATIBLE")).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(LlmProvider.parse("  Anthropic ")).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(LlmProvider.parse("")).isNull();
        assertThat(LlmProvider.parse(null)).isNull();
    }

    @Test
    void anUnrecognisedProviderIsLoudRatherThanSilentlyIgnored() {
        // Falling back to the default here would answer from the wrong model without saying so.
        assertThatThrownBy(() -> tenant("""
                {"org-id": "t1", "llm-backends": {"typo": {"provider": "anthropc"}}}
                """))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void turnTimeoutAcceptsBothTheSpringShorthandAndIso8601() {
        assertThat(backendWithTimeout("10m").getTurnTimeoutDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(backendWithTimeout("PT10M").getTurnTimeoutDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(backendWithTimeout("90s").getTurnTimeoutDuration()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void anUnparseableTurnTimeoutFallsBackRatherThanBreakingEveryTurn() {
        assertThat(backendWithTimeout("ten minutes").getTurnTimeoutDuration()).isNull();
        assertThat(backendWithTimeout("   ").getTurnTimeoutDuration()).isNull();
    }

    private TenantLlmBackend backendWithTimeout(String value) {
        return tenant("""
                {"org-id": "t1", "llm-backends": {"b": {"turn-timeout": "%s"}}}
                """.formatted(value)).getLlmBackends().get("b");
    }
}
