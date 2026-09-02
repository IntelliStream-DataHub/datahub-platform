// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.policy;

import ai.intellistream.datahub.tenant.CallerPermissions;
import ai.intellistream.dhconsole.chat.config.AgentSettings;
import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decides which of the platform's MCP tools an agent may be offered on this turn.
 *
 * <p>One rule, applied in one place:
 *
 * <pre>
 *   effective = advertised(MCP servers)
 *             ∩ agent.toolAllowlist        the explicit list
 *             ∩ capabilities(identity)     who the agent runs as
 * </pre>
 *
 * <p><strong>Default-deny at every step.</strong> A tool that no server advertises, that the
 * agent's list does not name, or that the identity cannot use is not offered — and neither half
 * can widen the other. An allowlist cannot reach data the caller has no grant on, and a grant
 * cannot reach a tool the allowlist omits.
 *
 * <h3>What replaced the hardcoded list</h3>
 * This class used to hold twenty tool names, identical for every tenant and every user, with a
 * comment noting that MCP gives a client no read-only hint to go on. It still does not — but the
 * classification now lives in datahub-api's {@code ToolCatalog}, beside the tools it describes,
 * and {@code AgentService} refuses to store an allowlist naming a mutating tool. So an agent's
 * allowlist is <em>already</em> a read-only list by construction, which is why checking membership
 * of it is a stronger check than the old name-based one, not a weaker one: it enforces the policy
 * itself rather than a proxy for it.
 *
 * <h3>This is not the security boundary</h3>
 * Narrowing the list is prompt economy and honesty — a tool the caller cannot use only spends
 * context and produces a denial the model then has to reason about. Enforcement stays in
 * datahub-api, on the request that performs each read, where it has always been.
 */
@Component
public class ToolPolicy {

    /**
     * The tools to offer the model this turn.
     *
     * @param advertised  what the MCP servers currently serve
     * @param settings    the resolved agent, carrying its explicit allowlist
     * @param permissions what the identity running this turn may do, or null if it could not be
     *                    established — in which case nothing is offered, since an unknown identity
     *                    is not a permissive one
     */
    public List<LlmToolDef> selectAllowed(List<LlmToolDef> advertised, AgentSettings settings,
                                          CallerPermissions permissions) {
        if (permissions == null || permissions.canReadNothing()) {
            // A caller with no read grant anywhere would be denied by every one of these. Offering
            // them produces a turn that looks like a broken assistant instead of a plain answer
            // that the user has no data access.
            return List.of();
        }
        return advertised.stream()
                .filter(tool -> settings.toolAllowlist().contains(tool.name()))
                .toList();
    }

    /**
     * Whether a tool the model asked for may actually run.
     *
     * <p>Deliberately the same predicate {@link #selectAllowed} filters on, rather than a
     * second one that could drift from it: a tool call is model output, and the last point it can
     * be refused is immediately before it runs.
     */
    public boolean isAllowed(String toolName, AgentSettings settings, CallerPermissions permissions) {
        return permissions != null
                && !permissions.canReadNothing()
                && settings.toolAllowlist().contains(toolName);
    }
}
