// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;

import java.util.Set;

/**
 * The result of a graph traversal ({@code POST /resources/fetch-related}): the connected
 * sub-graph reachable from a starting resource within a depth bound. {@code nodes} are the
 * nodes in that sub-graph, each typed by its type-label (see the node DTO family), {@code edges} the relationships between them (directional —
 * {@code start} → {@code end} — though traversal itself is undirected), and {@code labels} the
 * label catalogue for the returned nodes.
 *
 * <p>This is the lean wire shape of the response, shared by every client (the console and the
 * Java SDK) so none of them has to redeclare it.
 */
public record ResourceNetwork(Set<NodeModel> nodes, Set<EdgeProxy> edges, Set<LabelForm> labels) {
}
