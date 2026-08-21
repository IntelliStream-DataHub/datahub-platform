// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.models.EdgeProxy;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Lean view of a {@code resource_fetch_related} result: the connected neighbourhood as
 * {@link LeanResource} nodes plus the edges between them. Edges stay as {@link EdgeProxy}
 * (their empty {@code description}/{@code metadata} are stripped by {@link McpResultConverter}
 * anyway); nodes and labels are trimmed to the lean projections so a wide traversal doesn't
 * flood the context with per-node audit fields and empty collections.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanResourceNetwork(
        List<LeanResource> nodes,
        List<EdgeProxy> edges,
        List<LeanLabel> labels
) {
    public static LeanResourceNetwork from(ResourceNetwork n) {
        return new LeanResourceNetwork(
                n.nodes().stream().map(LeanResource::from).toList(),
                List.copyOf(n.edges()),
                n.labels().stream().map(LeanLabel::from).toList());
    }
}
