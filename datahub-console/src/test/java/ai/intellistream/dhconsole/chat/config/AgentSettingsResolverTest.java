// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.agent.AgentDefinition;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlmBackend;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.security.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Three layers, narrowest first: the agent row, the tenant's Vault backend, the deployment
 * defaults.
 *
 * <p>What is worth pinning is which layer wins for each field, and — more importantly — that a
 * missing layer degrades rather than fails. A tenant that has configured nothing must get exactly
 * the behaviour it had before any of this existed.
 */
class AgentSettingsResolverTest {

    private static final String ORG = "org-1";

    private ChatProperties properties;
    private TenantConfigService tenantConfigService;
    private UserSession userSession;
    private AgentSettingsResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new ChatProperties();
        properties.setProvider(LlmProvider.ANTHROPIC);
        properties.setApiKey("deployment-key");
        properties.setModel("claude-sonnet-5");
        properties.setEffort(ChatEffort.HIGH);
        properties.setTurnTimeout(Duration.ofMinutes(4));
        properties.setMaxIterations(6);

        tenantConfigService = mock(TenantConfigService.class);
        userSession = new UserSession();
        userSession.setOrganizationId(ORG);
        resolver = new AgentSettingsResolver(properties, tenantConfigService, userSession,
                mock(DatahubApi.class));
    }

    private static AgentDefinition agent(String backendRef, String instructions, String effort,
                                         Integer maxOutputTokens, Integer maxIterations) {
        return new AgentDefinition("console-assistant", "Console assistant", backendRef,
                instructions, List.of("event_search"), effort, maxOutputTokens, maxIterations, true);
    }

    private void tenantHas(String name, TenantLlmBackend backend) {
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(ORG);
        tenant.setLlmBackends(Map.of(name, backend));
        when(tenantConfigService.getConfig(ORG)).thenReturn(tenant);
    }

    private static TenantLlmBackend backend(LlmProvider provider, String apiKey, String model) {
        TenantLlmBackend backend = new TenantLlmBackend();
        backend.setProvider(provider);
        backend.setApiKey(apiKey);
        backend.setModel(model);
        return backend;
    }

    @Test
    void anAgentNamingNoBackendGetsTheDeploymentDefault() {
        // The upgrade path: the seeded console-assistant names no backend, so a tenant that has
        // configured nothing must behave exactly as it did before agents existed.
        AgentSettings settings = resolver.forAgent(agent(null, null, null, null, null));

        assertThat(settings.provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(settings.apiKey()).isEqualTo("deployment-key");
        assertThat(settings.model()).isEqualTo("claude-sonnet-5");
        assertThat(settings.defaultEffort()).isEqualTo(ChatEffort.HIGH);
        assertThat(settings.maxIterations()).isEqualTo(6);
        assertThat(settings.instructions()).isNull();
    }

    @Test
    void aTenantBackendOverridesTheDeploymentDefault() {
        tenantHas("house", backend(LlmProvider.ANTHROPIC, "tenant-key", "claude-opus-5"));

        AgentSettings settings = resolver.forAgent(agent("house", null, null, null, null));

        assertThat(settings.apiKey()).isEqualTo("tenant-key");
        assertThat(settings.model()).isEqualTo("claude-opus-5");
    }

    @Test
    void aTenantMayRunAnAirgappedModelWhileTheDeploymentDefaultIsHosted() {
        TenantLlmBackend onprem = backend(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3-32b");
        onprem.setBaseUrl("http://vllm.acme:8000/v1");
        onprem.setReasoningEffort("none");
        onprem.setTurnTimeout("10m");
        tenantHas("onprem", onprem);

        AgentSettings settings = resolver.forAgent(agent("onprem", null, null, null, null));

        assertThat(settings.provider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(settings.baseUrl()).isEqualTo("http://vllm.acme:8000/v1");
        assertThat(settings.reasoningEffort()).isEqualTo("none");
        assertThat(settings.turnTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void fieldsTheTenantBackendLeavesUnsetStillFallThrough() {
        // A backend that names only a model must not blank out the credential with it.
        tenantHas("partial", backend(null, null, "claude-opus-5"));

        AgentSettings settings = resolver.forAgent(agent("partial", null, null, null, null));

        assertThat(settings.model()).isEqualTo("claude-opus-5");
        assertThat(settings.apiKey()).isEqualTo("deployment-key");
        assertThat(settings.provider()).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void aBackendNamedButMissingFromVaultFallsBackRatherThanBreaking() {
        // Deleting a backend should degrade the agents using it, not take an assistant off the air
        // with no message anyone will see. The resolver logs; the turn still runs.
        tenantHas("house", backend(LlmProvider.ANTHROPIC, "tenant-key", "claude-opus-5"));

        AgentSettings settings = resolver.forAgent(agent("deleted-backend", null, null, null, null));

        assertThat(settings.apiKey()).isEqualTo("deployment-key");
    }

    @Test
    void theAgentsOwnBudgetsWinOverTheDeploymentDefaults() {
        AgentSettings settings = resolver.forAgent(agent(null, null, "low", 800, 2));

        assertThat(settings.defaultEffort()).isEqualTo(ChatEffort.LOW);
        assertThat(settings.maxOutputTokens()).isEqualTo(800);
        assertThat(settings.maxIterations()).isEqualTo(2);
        assertThat(settings.maxOutputTokensFor(ChatEffort.MAX)).isEqualTo(800);
    }

    @Test
    void anUnsetRoofLetsTheEffortLevelDecide() {
        AgentSettings settings = resolver.forAgent(agent(null, null, null, null, null));

        assertThat(settings.maxOutputTokens()).isNull();
        assertThat(settings.maxOutputTokensFor(ChatEffort.HIGH)).isEqualTo(4096);
        assertThat(settings.maxOutputTokensFor(ChatEffort.MAX)).isEqualTo(32_000);
    }

    @Test
    void bothDeploymentAndAgentInstructionsApplyWithTheNarrowerLast() {
        properties.setInstructions("This installation stores wind-farm telemetry.");

        AgentSettings settings = resolver.forAgent(
                agent(null, "Tags follow ISO 14224.", null, null, null));

        assertThat(settings.instructions())
                .isEqualTo("This installation stores wind-farm telemetry.\n\nTags follow ISO 14224.");
    }

    @Test
    void eitherInstructionAloneIsUsedWithoutStrayBlankLines() {
        assertThat(resolver.forAgent(agent(null, "Only the agent's.", null, null, null))
                .instructions()).isEqualTo("Only the agent's.");

        properties.setInstructions("Only the deployment's.");
        assertThat(resolver.forAgent(agent(null, null, null, null, null))
                .instructions()).isEqualTo("Only the deployment's.");
    }

    @Test
    void aSessionWithNoOrganizationFallsBackRatherThanThrowing() {
        // An error dispatch can reach here with a half-built session. Losing the tenant's model is
        // survivable; failing the request is not.
        userSession.setOrganizationId(null);

        assertThat(resolver.forAgent(agent("house", null, null, null, null)).apiKey())
                .isEqualTo("deployment-key");
    }

    @Test
    void theAgentsToolAllowlistIsCarriedThroughUntouched() {
        assertThat(resolver.forAgent(agent(null, null, null, null, null)).toolAllowlist())
                .containsExactly("event_search");
    }
}
