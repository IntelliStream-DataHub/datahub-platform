// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.security.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The model comes from the tenant; the budget comes from the deployment.
 *
 * <p>Two properties worth pinning, pulling opposite ways. There is no deployment credential, so no
 * arrangement of missing fields can end with one tenant's turn billed to the platform's account or
 * another tenant's. But everything about how that model is <em>run</em> is the tenant's to set,
 * including choices that cost it a great deal of money.
 */
class ChatSettingsResolverTest {

    private ChatProperties properties;
    private ChatSettingsResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new ChatProperties();
        properties.setTurnTimeout(Duration.ofMinutes(4));

        resolver = new ChatSettingsResolver(properties, mock(TenantConfigService.class),
                new UserSession());
    }

    private static TenantLlm llm(LlmProvider provider, String apiKey, String model) {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(provider);
        llm.setApiKey(apiKey);
        llm.setModel(model);
        return llm;
    }

    @Test
    void theTenantsOwnModelIsWhatGetsUsed() {
        ChatSettings settings =
                resolver.forTenant(llm(LlmProvider.ANTHROPIC, "tenant-key", "claude-opus-5"));

        assertThat(settings.provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(settings.apiKey()).isEqualTo("tenant-key");
        assertThat(settings.model()).isEqualTo("claude-opus-5");
    }

    @Test
    void aTenantMayRunAnAirgappedModelOnNoCredentialAtAll() {
        TenantLlm onprem = llm(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3-32b");
        onprem.setBaseUrl("http://vllm.acme:8000/v1");
        onprem.setReasoningEffort("none");
        onprem.setTurnTimeout("10m");

        ChatSettings settings = resolver.forTenant(onprem);

        assertThat(settings.provider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(settings.baseUrl()).isEqualTo("http://vllm.acme:8000/v1");
        assertThat(settings.reasoningEffort()).isEqualTo("none");
        assertThat(settings.turnTimeout()).isEqualTo(Duration.ofMinutes(10));
        // The key stays absent rather than inheriting one: a self-hosted server would reject it,
        // and there is nothing to inherit from anyway.
        assertThat(settings.apiKey()).isNull();
    }

    @Test
    void aTenantWithNoModelOfItsOwnGetsNoSettingsRatherThanSomeoneElses() {
        // ChatAccess has already hidden the panel by here, so this is the race — the secret emptied
        // mid-session — and it must fail rather than quietly find a credential somewhere.
        assertThatThrownBy(() -> resolver.forCurrentTenant())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-config");
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
        // The one thing still inherited: how long the deployment is willing to wait. A tenant may
        // raise it for a slow model, but saying nothing lands on the deployment's patience.
        ChatSettings settings = resolver.forTenant(llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5"));

        assertThat(settings.turnTimeout()).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void aTenantThatSaysNothingAboutSpendingGetsTheDeploymentDefaults() {
        properties.setMaxOutputTokens(800);
        properties.setEffort(ChatEffort.LOW);
        properties.setMaxIterations(3);
        properties.setInstructions("House style.");

        ChatSettings settings = resolver.forTenant(llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5"));

        assertThat(settings.maxOutputTokens()).isEqualTo(800);
        assertThat(settings.defaultEffort()).isEqualTo(ChatEffort.LOW);
        assertThat(settings.maxIterations()).isEqualTo(3);
        assertThat(settings.instructions()).isEqualTo("House style.");
    }

    @Test
    void aTenantMaySpendAsMuchOfItsOwnMoneyAsItLikes() {
        // The point of bring-your-own-model: the credential is theirs, so the bill is theirs, so
        // the ceiling is theirs. A deployment default of 800 tokens does not cap a tenant that
        // wants max effort with no roof at all.
        properties.setMaxOutputTokens(800);
        properties.setEffort(ChatEffort.LOW);
        properties.setMaxIterations(3);

        TenantLlm spendy = llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5");
        spendy.setEffort("max");
        spendy.setMaxIterations("20");
        spendy.setInstructions("We speak in ISO 14224 tags.");

        ChatSettings settings = resolver.forTenant(spendy);

        assertThat(settings.defaultEffort()).isEqualTo(ChatEffort.MAX);
        assertThat(settings.maxIterations()).isEqualTo(20);
        assertThat(settings.instructions()).isEqualTo("We speak in ISO 14224 tags.");
        // Raising the roof takes an explicit number; there is no "unlimited" to write, and the
        // deployment default still applies to a tenant that names none.
        assertThat(settings.maxOutputTokens()).isEqualTo(800);

        spendy.setMaxOutputTokens("64000");
        assertThat(resolver.forTenant(spendy).maxOutputTokensFor(ChatEffort.LOW)).isEqualTo(64_000);
    }

    @Test
    void anUnsetRoofLetsTheEffortLevelDecide() {
        ChatSettings settings = resolver.forTenant(llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5"));

        assertThat(settings.maxOutputTokens()).isNull();
        assertThat(settings.maxOutputTokensFor(ChatEffort.HIGH)).isEqualTo(4096);
        assertThat(settings.maxOutputTokensFor(ChatEffort.MAX)).isEqualTo(32_000);
    }

    @Test
    void aTypoInABudgetCostsThatFieldAndNotTheAssistant() {
        // With nothing to fall back to, treating a bad number as a bad entry would take the panel
        // away over a stray character in an optional field.
        properties.setMaxIterations(6);
        TenantLlm typo = llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5");
        typo.setMaxIterations("six");
        typo.setMaxOutputTokens("-1");
        typo.setEffort("ludicrous");

        ChatSettings settings = resolver.forTenant(typo);

        assertThat(typo.isUsable()).isTrue();
        assertThat(settings.maxIterations()).isEqualTo(6);
        assertThat(settings.maxOutputTokens()).isNull();
        assertThat(settings.defaultEffort()).isEqualTo(properties.getEffort());
    }
}
