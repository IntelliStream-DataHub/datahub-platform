// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.agent;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * An agent: a named LLM assistant with its own instructions, its own model backend and its own
 * explicit list of tools.
 *
 * <p>The tool list is half of what the agent may do. The other half is the permissions of the
 * identity it runs as — today always the signed-in caller, see
 * {@code ai.intellistream.datahub.tenant.CallerPermissions}. The two intersect, and neither can
 * widen the other: an allowlist cannot grant access to data the caller has no grant on, and a
 * grant cannot reach a tool the allowlist omits.
 *
 * <p><strong>No credential appears here, and none can.</strong> Which model an agent runs on is a
 * tenant-level fact held in Vault, resolved server-side in a process that already holds Vault
 * credentials. This record is safe to hand to any client that may see the agent at all.
 *
 * @param externalId       stable name, e.g. {@code console-assistant}
 * @param displayName      shown to people
 * @param instructions     appended to the built-in system prompt, never substituted for it
 * @param toolAllowlist    the MCP tools this agent may be offered. Default-deny: empty means no
 *                         tools, not all of them
 * @param defaultEffort    where the effort picker starts — {@code low}, {@code medium},
 *                         {@code high}, {@code xhigh} or {@code max}. Null leaves the deployment
 *                         default. Effort stays a per-message choice either way
 * @param maxOutputTokens  ceiling on one call's output, or null to let the effort level choose
 * @param maxIterations    ceiling on model-to-tool-to-model round trips in one turn, or null for
 *                         the deployment default
 * @param enabled          false means defined but not offered and not answering
 */
@Schema(name = "AgentDefinition",
        description = """
                A named LLM assistant: its instructions, its budgets, and exactly which MCP tools
                it may be offered. What it can actually reach is this list intersected with the
                permissions of whoever runs it — neither widens the other. Which model it runs on
                is a tenant-level setting, not the agent's to choose.""")
public record AgentDefinition(
        @Schema(description = "Stable name.", example = "console-assistant") String externalId,
        @Schema(description = "Name shown to people.", example = "Console assistant") String displayName,
        @Schema(description = "Extra system-prompt instructions, appended to the built-in prompt.")
        String instructions,
        @Schema(description = "MCP tools this agent may use. Empty means none.")
        List<String> toolAllowlist,
        @Schema(description = "Starting point for the effort picker.", example = "high")
        String defaultEffort,
        @Schema(description = "Output-token ceiling for one call, or null to let effort decide.",
                example = "4096") Integer maxOutputTokens,
        @Schema(description = "Tool round-trip ceiling for one turn.", example = "6")
        Integer maxIterations,
        @Schema(description = "Whether the agent is offered at all.") boolean enabled) {

    /** Defensive copy, and never null, so callers can iterate without checking. */
    public AgentDefinition {
        toolAllowlist = toolAllowlist == null ? List.of() : List.copyOf(toolAllowlist);
    }

    /** Whether this agent may be offered the named tool at all, before permissions narrow it. */
    public boolean allows(String toolName) {
        return toolAllowlist.contains(toolName);
    }
}
