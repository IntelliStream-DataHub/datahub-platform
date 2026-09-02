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
import static org.mockito.Mockito.mock;

/**
 * Two layers: the tenant's own model, and the deployment default for whatever it leaves unset.
 *
 * <p>What matters is not the arithmetic but that a missing layer degrades rather than fails — a
 * tenant that has configured nothing must behave exactly as it did before any of this existed.
 */
class ChatSettingsResolverTest {

    private ChatProperties properties;
    private ChatSettingsResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new ChatProperties();
        properties.setProvider(LlmProvider.ANTHROPIC);
        properties.setApiKey("deployment-key");
        properties.setModel("claude-sonnet-5");
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
    void aTenantWithNoModelOfItsOwnGetsTheDeploymentDefault() {
        // The upgrade path: every tenant is in this state on the day this ships, and every one of
        // them must keep answering exactly as before.
        ChatSettings settings = resolver.forTenant(null);

        assertThat(settings.provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(settings.apiKey()).isEqualTo("deployment-key");
        assertThat(settings.model()).isEqualTo("claude-sonnet-5");
        assertThat(settings.turnTimeout()).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void aTenantsOwnModelOverridesTheDefault() {
        ChatSettings settings =
                resolver.forTenant(llm(LlmProvider.ANTHROPIC, "tenant-key", "claude-opus-5"));

        assertThat(settings.apiKey()).isEqualTo("tenant-key");
        assertThat(settings.model()).isEqualTo("claude-opus-5");
    }

    @Test
    void aTenantMayRunAnAirgappedModelWhileTheDefaultIsHosted() {
        TenantLlm onprem = llm(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3-32b");
        onprem.setBaseUrl("http://vllm.acme:8000/v1");
        onprem.setReasoningEffort("none");
        onprem.setTurnTimeout("10m");

        ChatSettings settings = resolver.forTenant(onprem);

        assertThat(settings.provider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(settings.baseUrl()).isEqualTo("http://vllm.acme:8000/v1");
        assertThat(settings.reasoningEffort()).isEqualTo("none");
        assertThat(settings.turnTimeout()).isEqualTo(Duration.ofMinutes(10));
        // No key of its own, and the deployment's would be meaningless to a local server — but
        // falling back is harmless because the client only sends it if it is set.
        assertThat(settings.apiKey()).isEqualTo("deployment-key");
    }

    @Test
    void fieldsTheTenantLeavesUnsetStillFallThrough() {
        // An entry naming only a model must not blank out the credential with it.
        ChatSettings settings = resolver.forTenant(llm(null, null, "claude-opus-5"));

        assertThat(settings.model()).isEqualTo("claude-opus-5");
        assertThat(settings.apiKey()).isEqualTo("deployment-key");
        assertThat(settings.provider()).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void budgetsStayDeploymentWide() {
        // A tenant chooses which model it talks to, not how much the deployment will spend on it.
        properties.setMaxOutputTokens(800);

        ChatSettings settings = resolver.forTenant(llm(LlmProvider.ANTHROPIC, "k", "claude-opus-5"));

        assertThat(settings.maxOutputTokens()).isEqualTo(800);
        assertThat(settings.maxOutputTokensFor(ChatEffort.MAX)).isEqualTo(800);
    }

    @Test
    void anUnsetRoofLetsTheEffortLevelDecide() {
        ChatSettings settings = resolver.forTenant(null);

        assertThat(settings.maxOutputTokens()).isNull();
        assertThat(settings.maxOutputTokensFor(ChatEffort.HIGH)).isEqualTo(4096);
        assertThat(settings.maxOutputTokensFor(ChatEffort.MAX)).isEqualTo(32_000);
    }

    @Test
    void aSessionWithNoOrganizationFallsBackRatherThanThrowing() {
        // An error dispatch can reach here with a half-built session. Losing the tenant's model is
        // survivable; failing the request is not.
        assertThat(resolver.forCurrentTenant().apiKey()).isEqualTo("deployment-key");
    }
}
