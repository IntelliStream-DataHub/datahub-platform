// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code llm} block of a tenant's Vault entry.
 *
 * <p>Deserialization is the whole contract here: these values are hand-written into Vault by an
 * operator, so every reasonable spelling has to work and an unreasonable one has to be loud.
 */
class TenantLlmTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private Tenant tenant(String body) {
        return json.readValue(body, Tenant.class);
    }

    @Test
    void readsTheTenantsModelFromItsVaultEntry() {
        Tenant tenant = tenant("""
                {"org-id": "t1", "llm":
                   {"provider": "anthropic", "api-key": "sk-ant-x", "model": "claude-opus-5"}}
                """);

        assertThat(tenant.getLlm().getProvider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(tenant.getLlm().getApiKey()).isEqualTo("sk-ant-x");
        assertThat(tenant.getLlm().getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    void readsASelfHostedModelWithNoCredential() {
        Tenant tenant = tenant("""
                {"org-id": "t1", "llm":
                   {"provider": "openai-compatible", "base-url": "http://vllm:8000/v1",
                    "model": "qwen3-32b", "reasoning-effort": "none"}}
                """);

        TenantLlm llm = tenant.getLlm();
        assertThat(llm.getProvider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(llm.getBaseUrl()).isEqualTo("http://vllm:8000/v1");
        assertThat(llm.getReasoningEffort()).isEqualTo("none");
        // No key: the airgapped path does not need one.
        assertThat(llm.getApiKey()).isNull();
    }

    @Test
    void aTenantWithNoBlockHasNoModelOfItsOwn() {
        assertThat(tenant("""
                {"org-id": "t1"}
                """).getLlm()).isNull();
    }

    @Test
    void unsetFieldsStayNullSoTheDeploymentDefaultCanFillThem() {
        TenantLlm backend = tenant("""
                {"org-id": "t1", "llm": {"model": "claude-opus-5"}}
                """).getLlm();

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
                {"org-id": "t1", "llm": {"provider": "anthropc"}}
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

    private TenantLlm backendWithTimeout(String value) {
        return tenant("""
                {"org-id": "t1", "llm": {"turn-timeout": "%s"}}
                """.formatted(value)).getLlm();
    }
}
