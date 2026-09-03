// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code tenant-llm/<org-id>} secret.
 *
 * <p>Parsed on its own rather than through {@link Tenant}, which is how {@code TenantLlmStore}
 * reads it: the model config lives in its own secret so it can be written without granting write
 * access to the connection registry, and {@code Tenant.llm} is filled in afterwards.
 *
 * <p>Deserialization is the whole contract here: these values are hand-written into Vault by an
 * operator or written by the settings form, so every reasonable spelling has to work and an
 * unreasonable one has to be loud.
 */
class TenantLlmTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private TenantLlm llm(String body) {
        return json.readValue(body, TenantLlm.class);
    }

    @Test
    void readsTheTenantsModelFromItsOwnSecret() {
        TenantLlm llm = llm("""
                {"provider": "anthropic", "api-key": "sk-ant-x", "model": "claude-opus-5"}
                """);

        assertThat(llm.getProvider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(llm.getApiKey()).isEqualTo("sk-ant-x");
        assertThat(llm.getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    void theModelConfigDoesNotComeFromTheConnectionRegistry() {
        // The split is the point: an llm block left behind in tenant-resources must not be read,
        // or a tenant could keep a credential in the secret nothing is allowed to write.
        Tenant tenant = json.readValue("""
                {"org-id": "t1", "llm": {"provider": "anthropic", "api-key": "stale"}}
                """, Tenant.class);

        assertThat(tenant.getLlm()).isNull();
    }

    @Test
    void readsASelfHostedModelWithNoCredential() {
        TenantLlm llm = llm("""
                {"provider": "openai-compatible", "base-url": "http://vllm:8000/v1",
                 "model": "qwen3-32b", "reasoning-effort": "none"}
                """);
        assertThat(llm.getProvider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(llm.getBaseUrl()).isEqualTo("http://vllm:8000/v1");
        assertThat(llm.getReasoningEffort()).isEqualTo("none");
        // No key: the airgapped path does not need one.
        assertThat(llm.getApiKey()).isNull();
    }

    @Test
    void aTenantWithNoSecretHasNoModelOfItsOwn() {
        // TenantLlmStore returns null on a 404, and the deployment default then applies.
        Tenant tenant = json.readValue("""
                {"org-id": "t1"}
                """, Tenant.class);

        assertThat(tenant.getLlm()).isNull();
    }

    @Test
    void unsetFieldsStayNullSoTheDeploymentDefaultCanFillThem() {
        TenantLlm backend = llm("""
                {"model": "claude-opus-5"}
                """);

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
        assertThatThrownBy(() -> llm("""
                {"provider": "anthropc"}
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
        return llm("""
                {"turn-timeout": "%s"}
                """.formatted(value));
    }

    /**
     * The gate {@code ChatAccess} reads. There is no deployment credential behind it, so "not quite
     * configured" has to mean no assistant — not an assistant on someone else's account.
     */
    @Test
    void anEntryIsUsableOnlyOnceItNamesAModelSomethingCanReach() {
        assertThat(usable(LlmProvider.ANTHROPIC, "sk-ant-x", "claude-opus-5", null)).isTrue();
        // Ollama and some vLLM deployments take no key at all.
        assertThat(usable(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3-32b", "http://x:8000/v1")).isTrue();

        assertThat(usable(null, "sk-ant-x", "claude-opus-5", null)).isFalse();
        assertThat(usable(LlmProvider.ANTHROPIC, null, "claude-opus-5", null)).isFalse();
        assertThat(usable(LlmProvider.ANTHROPIC, "sk-ant-x", null, null)).isFalse();
        assertThat(usable(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3-32b", null)).isFalse();
        // A key blanked out in Vault reads as an empty string, not as an absent field.
        assertThat(usable(LlmProvider.ANTHROPIC, "   ", "claude-opus-5", null)).isFalse();
    }

    private static boolean usable(LlmProvider provider, String apiKey, String model, String baseUrl) {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(provider);
        llm.setApiKey(apiKey);
        llm.setModel(model);
        llm.setBaseUrl(baseUrl);
        return llm.isUsable();
    }
}
