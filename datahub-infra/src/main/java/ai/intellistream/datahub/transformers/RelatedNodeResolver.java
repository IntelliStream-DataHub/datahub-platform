// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelatedNode;
import ai.intellistream.datahub.models.RelationDirection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the node-centric {@code relatedResources} adjacency ({@link RelatedNode}: id + externalId +
 * relationshipType + {@link RelationDirection}) from an already-loaded edge set. Used where the graph is in
 * hand (the graph/network responses), so it is a pure static helper — no repositories, no per-read queries.
 */
public final class RelatedNodeResolver {

    private RelatedNodeResolver() {
    }

    /**
     * Convenience for the single-node transformers: the {@code relatedResources} for one node from the edges
     * touching it. Neighbour externalIds are left null (not available in that context); id + edgeId + type +
     * direction are populated.
     */
    public static List<RelatedNode> forNode(Long nodeId, Collection<EdgeProxy> edges) {
        if (nodeId == null) {
            return new ArrayList<>();
        }
        return fromEdges(List.of(nodeId), edges, Map.of()).getOrDefault(nodeId, new ArrayList<>());
    }

    /**
     * Build {@code relatedResources} per node id from an already-loaded {@link EdgeProxy} set. Neighbour
     * externalIds are filled from {@code externalIdById} (pass an empty/partial map to leave them null).
     */
    public static Map<Long, List<RelatedNode>> fromEdges(
            Collection<Long> nodeIds, Collection<EdgeProxy> edges, Map<Long, String> externalIdById) {

        Map<Long, List<RelatedNode>> out = new HashMap<>();
        for (Long id : nodeIds) {
            if (id != null) {
                out.putIfAbsent(id, new ArrayList<>());
            }
        }
        if (edges != null) {
            for (EdgeProxy e : edges) {
                addEdge(out, e.getId(), e.getStart(), e.getEnd(), e.getType(), externalIdById);
            }
        }
        return out;
    }

    /**
     * A directed edge {@code start -> end} makes {@code end} an OUTBOUND relation of {@code start} and
     * {@code start} an INBOUND relation of {@code end}. Only nodes present in {@code out} (the result set)
     * get an entry appended; self-loops are skipped. Both entries carry the edge's id.
     */
    private static void addEdge(Map<Long, List<RelatedNode>> out, Long edgeId, Long start, Long end,
                                String relationshipType, Map<Long, String> externalIdById) {
        if (start == null || end == null || start.equals(end)) {
            return;
        }
        List<RelatedNode> startList = out.get(start);
        if (startList != null) {
            startList.add(relatedNode(end, edgeId, relationshipType, RelationDirection.OUTBOUND, externalIdById));
        }
        List<RelatedNode> endList = out.get(end);
        if (endList != null) {
            endList.add(relatedNode(start, edgeId, relationshipType, RelationDirection.INBOUND, externalIdById));
        }
    }

    private static RelatedNode relatedNode(Long id, Long edgeId, String relationshipType,
                                           RelationDirection direction, Map<Long, String> externalIdById) {
        RelatedNode rn = new RelatedNode();
        rn.setId(id);
        if (externalIdById != null) {
            rn.setExternalId(externalIdById.get(id));
        }
        if (relationshipType != null) {
            rn.setRelationshipType(relationshipType);
        }
        rn.setDirection(direction);
        rn.setEdgeId(edgeId);
        return rn;
    }
}
