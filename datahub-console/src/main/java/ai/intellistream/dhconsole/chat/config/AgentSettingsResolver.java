// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.agent.AgentDefinition;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.security.UserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;


/**
 * Assembles the {@link AgentSettings} for one turn out of the three layers that can have an
 * opinion about it.
 *
 * <p>Narrowest first, each filling only what the previous left unset:
 * <ol>
 *   <li>the <strong>agent</strong> row in the tenant's own database — its instructions, its tool
 *       list, its budgets. Fetched from datahub-api, which owns it.</li>
 *   <li>the <strong>backend</strong> that row names in the tenant's Vault entry — provider,
 *       credential, model, endpoint.</li>
 *   <li>the <strong>deployment</strong> defaults in {@link ChatProperties}.</li>
 * </ol>
 *
 * <p>Nothing here fails a turn for a missing layer. A tenant with no backend of its own uses the
 * deployment's; an agent with no budget uses the deployment's; a backend named but absent from
 * Vault falls back rather than breaking, because deleting a backend should degrade the agents
 * using it, not silently take an assistant off the air with no message anyone will see.
 *
 * <p>The one thing that <em>is</em> fatal is a missing agent row, and deliberately: the console is
 * pointed at an agent by name, and a name that resolves to nothing is a configuration error with
 * exactly one fix. Guessing a default would hide it.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class AgentSettingsResolver {

    private final ChatProperties properties;
    private final TenantConfigService tenantConfigService;
    private final UserSession userSession;
    private final DatahubApi datahubApi;

    public AgentSettingsResolver(ChatProperties properties, TenantConfigService tenantConfigService,
                                 UserSession userSession, DatahubApi datahubApi) {
        this.properties = properties;
        this.tenantConfigService = tenantConfigService;
        this.userSession = userSession;
        this.datahubApi = datahubApi;
    }

    /** The agent the console's chat panel runs, resolved for the signed-in user's tenant. */
    public AgentSettings forConsoleAgent() {
        return forAgent(datahubApi.getAgent(properties.getAgent()));
    }

    /** Visible for tests, and the seam an autonomous runner would reuse with its own agent. */
    public AgentSettings forAgent(AgentDefinition agent) {
        TenantLlm backend = tenantLlm();

        return new AgentSettings(
                agent.externalId(),
                pick(backend == null ? null : backend.getProvider(), properties.getProvider()),
                pickText(backend == null ? null : backend.getApiKey(), properties.getApiKey()),
                pickText(backend == null ? null : backend.getModel(), properties.getModel()),
                pickText(backend == null ? null : backend.getBaseUrl(), properties.getBaseUrl()),
                pickText(backend == null ? null : backend.getReasoningEffort(), properties.getReasoningEffort()),
                pick(backend == null ? null : backend.getTurnTimeoutDuration(), properties.getTurnTimeout()),
                // Instructions are the one field that does not simply fall back. A deployment-wide
                // instruction predates agents and describes the whole installation; an agent's own
                // describes that assistant. Where both exist both apply, deployment first, because
                // the narrower statement should be the one the model reads last.
                joinInstructions(properties.getInstructions(), agent.instructions()),
                agent.toolAllowlist(),
                ChatEffort.parse(agent.defaultEffort(), properties.getEffort()),
                pick(agent.maxOutputTokens(), properties.getMaxOutputTokens()),
                pick(agent.maxIterations(), properties.getMaxIterations()),
                properties.getMaxMessages(),
                properties.getMaxToolResultChars());
    }

    /**
     * This tenant's LLM configuration, or null to use the deployment default.
     *
     * <p>One per tenant, so there is nothing to look up by name and nothing to get wrong: a tenant
     * either has its own model or it does not.
     */
    private TenantLlm tenantLlm() {
        String orgId = userSession.getOrganizationId();
        if (orgId == null) {
            return null;
        }
        Tenant tenant = tenantConfigService.getConfig(orgId);
        return tenant == null ? null : tenant.getLlm();
    }

    private static String joinInstructions(String deployment, String agent) {
        boolean hasDeployment = deployment != null && !deployment.isBlank();
        boolean hasAgent = agent != null && !agent.isBlank();
        if (hasDeployment && hasAgent) {
            return deployment.strip() + "\n\n" + agent.strip();
        }
        if (hasAgent) {
            return agent.strip();
        }
        return hasDeployment ? deployment.strip() : null;
    }

    private static <T> T pick(T narrower, T fallback) {
        return narrower != null ? narrower : fallback;
    }

    /** As {@link #pick}, but a blank string counts as unset — Vault fields are hand-written. */
    private static String pickText(String narrower, String fallback) {
        return narrower == null || narrower.isBlank() ? fallback : narrower.strip();
    }
}
