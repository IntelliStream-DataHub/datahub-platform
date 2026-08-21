// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.policy;

import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Decides which of datahub-api's MCP tools the chat may use.
 *
 * <p><strong>Default-deny.</strong> The allowlist below names the read-only tools explicitly;
 * anything not on it — including a tool added to datahub-api after this file was written — is
 * treated as mutating and is never offered to the model. That is the whole safety property, and it
 * is why this is an allowlist of read-only names rather than a blocklist of dangerous ones.
 *
 * <p>MCP itself gives us nothing to go on here: Spring AI's {@code ToolDefinition} carries only
 * name, description and input schema, with no read-only hint, so the classification has to live on
 * this side.
 *
 * <p>Widening this set is a deliberate, reviewable edit — {@code ToolPolicyTest} pins it.
 */
@Component
public class ToolPolicy {

    private static final Set<String> READ_ONLY = Set.of(
            "analysis_related_series",
            "dataset_list",
            "dataset_search",
            "edge_get",
            "edge_list_types",
            "event_filter",
            "event_get",
            "event_search",
            "label_list",
            "resource_fetch_nearest",
            "resource_fetch_related",
            "resource_get",
            "resource_search",
            "timeseries_fetch_datapoints",
            "timeseries_get",
            "timeseries_get_latest",
            "timeseries_list",
            "timeseries_search",
            "unit_get",
            "unit_list");

    public boolean isReadOnly(String toolName) {
        return READ_ONLY.contains(toolName);
    }

    /** The tools the model is allowed to see. Everything else is filtered out before the request. */
    public List<LlmToolDef> selectAllowed(List<LlmToolDef> advertised) {
        return advertised.stream().filter(t -> isReadOnly(t.name())).toList();
    }

    public Set<String> readOnlyToolNames() {
        return READ_ONLY;
    }
}
