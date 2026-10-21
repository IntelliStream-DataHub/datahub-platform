// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.TenantLlm;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model comes from the tenant; the budget comes from the caller's defaults.
 *
 * <p>Two properties worth pinning, pulling opposite ways. There is no deployment credential, so no
 * arrangement of missing fields can end with one tenant's turn billed to the platform's account or
 * another tenant's. But everything about how that model is <em>run</em> is the tenant's to set,
 * including choices that cost it a great deal of money.
 */
class LlmSettingsTest {

    private static final LlmSettings.Defaults DEFAULTS =
            new LlmSettings.Defaults(LlmEffort.HIGH, Duration.ofMinutes(4), 6, null, null);

    private static TenantLlm llm(LlmProvider provider, String apiKey, String model) {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(provider);
        llm.setApiKey(apiKey);
        llm.setModel(model);
        return llm;
    }

    @Test
    void theTenantsOwnModelIsWhatGetsUsed() {
        LlmSettings settings = LlmSettings.of(llm(LlmProvider.ANTHROPIC, " tenant-key ", "claude-opus-5"), DEFAULTS);

        assertThat(settings.provider()).isEqualTo(LlmProvider.ANTHROPIC);
        // Vault fields are hand-written; a stray space is not a different credential.
        assertThat(settings.apiKey()).isEqualTo("tenant-key");
        assertThat(settings.model()).isEqualTo("claude-opus-5");
    }

    @Test
    void aTenantMayRunAnAirgappedModelOnNoCredentialAtAll() {
        TenantLlm onprem = llm(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3-32b");
        onprem.setBaseUrl("http://vllm.acme:8000/v1");
        onprem.setReasoningEffort("none");
        onprem.setTurnTimeout("10m");

        LlmSettings settings = LlmSettings.of(onprem, DEFAULTS);

        assertThat(settings.provider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(settings.baseUrl()).isEqualTo("http://vllm.acme:8000/v1");
        assertThat(settings.reasoningEffort()).isEqualTo("none");
        assertThat(settings.turnTimeout()).isEqualTo(Duration.ofMinutes(10));
        // The key stays absent rather than inheriting one: a self-hosted server would reject it,
        // and there is nothing to inherit from anyway.
        assertThat(settings.apiKey()).isNull();
    }

    @Test
    void aHalfWrittenEntryIsNoModel() {
        // Naming a model but no credential is the shape a half-finished edit leaves behind.
        assertThat(llm(LlmProvider.ANTHROPIC, null, "claude-opus-5").isUsable()).isFalse();
        assertThat(llm(LlmProvider.ANTHROPIC, "k", null).isUsable()).isFalse();
        assertThat(llm(null, "k", "claude-opus-5").isUsable()).isFalse();
    }

    @Test
    void theTurnTimeoutFallsBackBecauseItIsNotACredential() {
        // The one thing still inherited: how long the caller is willing to wait. A tenant may
        // raise it for a slow model, but saying nothing lands on the default.
        LlmSettings settings = LlmSettings.of(llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5"), DEFAULTS);

        assertThat(settings.turnTimeout()).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void aTenantThatSaysNothingAboutSpendingGetsTheDefaults() {
        var defaults = new LlmSettings.Defaults(LlmEffort.LOW, Duration.ofMinutes(4), 3, 800, "House style.");

        LlmSettings settings = LlmSettings.of(llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5"), defaults);

        assertThat(settings.maxOutputTokens()).isEqualTo(800);
        assertThat(settings.defaultEffort()).isEqualTo(LlmEffort.LOW);
        assertThat(settings.maxIterations()).isEqualTo(3);
        assertThat(settings.instructions()).isEqualTo("House style.");
    }

    @Test
    void aTenantMaySpendAsMuchOfItsOwnMoneyAsItLikes() {
        // The point of bring-your-own-model: the credential is theirs, so the bill is theirs, so
        // the ceiling is theirs. A default of 800 tokens does not cap a tenant that wants max
        // effort with no roof at all.
        var defaults = new LlmSettings.Defaults(LlmEffort.LOW, Duration.ofMinutes(4), 3, 800, null);
        TenantLlm spendy = llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5");
        spendy.setEffort("max");
        spendy.setMaxIterations("20");
        spendy.setInstructions("We speak in ISO 14224 tags.");

        LlmSettings settings = LlmSettings.of(spendy, defaults);

        assertThat(settings.defaultEffort()).isEqualTo(LlmEffort.MAX);
        assertThat(settings.maxIterations()).isEqualTo(20);
        assertThat(settings.instructions()).isEqualTo("We speak in ISO 14224 tags.");
        // Raising the roof takes an explicit number; there is no "unlimited" to write, and the
        // default still applies to a tenant that names none.
        assertThat(settings.maxOutputTokens()).isEqualTo(800);

        spendy.setMaxOutputTokens("64000");
        assertThat(LlmSettings.of(spendy, defaults).maxOutputTokensFor(LlmEffort.LOW)).isEqualTo(64_000);
    }

    @Test
    void anUnsetRoofLetsTheEffortLevelDecide() {
        LlmSettings settings = LlmSettings.of(llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5"), DEFAULTS);

        assertThat(settings.maxOutputTokens()).isNull();
        assertThat(settings.maxOutputTokensFor(LlmEffort.HIGH)).isEqualTo(4096);
        assertThat(settings.maxOutputTokensFor(LlmEffort.MAX)).isEqualTo(32_000);
    }

    @Test
    void aTypoInABudgetCostsThatFieldAndNotTheModel() {
        // With nothing to fall back to, treating a bad number as a bad entry would take the
        // tenant's model away over a stray character in an optional field.
        TenantLlm typo = llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5");
        typo.setMaxIterations("six");
        typo.setMaxOutputTokens("-1");
        typo.setEffort("ludicrous");

        LlmSettings settings = LlmSettings.of(typo, DEFAULTS);

        assertThat(typo.isUsable()).isTrue();
        assertThat(settings.maxIterations()).isEqualTo(6);
        assertThat(settings.maxOutputTokens()).isNull();
        assertThat(settings.defaultEffort()).isEqualTo(LlmEffort.HIGH);
    }

    @Test
    void neverPrintsTheCredential() {
        LlmSettings settings = LlmSettings.of(llm(LlmProvider.ANTHROPIC, "sk-ant-secret", "claude-opus-5"), DEFAULTS);

        assertThat(settings.toString()).doesNotContain("sk-ant-secret");
        assertThat(settings.backendKey().toString()).doesNotContain("sk-ant-secret");
    }
}
