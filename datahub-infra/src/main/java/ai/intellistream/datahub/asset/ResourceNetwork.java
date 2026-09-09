// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.asset;

import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.RelatedNode;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.transformers.NodeReadMapper;
import ai.intellistream.datahub.transformers.RelatedNodeResolver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ResourceNetwork(Set<NodeModel> nodes, Set<EdgeProxy> edges, Set<Label> labels) {

    /**
     * Maps the result of {@code apoc.path.subgraphAll}. The procedure returns a single record with
     * two list columns: {@code nodes} (every node in the component) and {@code relationships}
     * (every relationship between those nodes). Both are already de-duplicated by APOC, but we
     * still collect into {@link Set}s to be defensive.
     */
    public static ResourceNetwork from(List<Record> records, Set<Label> labels){
        return from(records, labels, GraphReadScope.readEverything());
    }

    /**
     * As above, but drops what {@code scope} does not permit: any node in a dataset the caller
     * cannot read, and any relationship with an endpoint that is not itself visible.
     *
     * <p>The filtering happens here, before {@link #attachRelatedResources}, and that ordering is
     * the point. {@code relatedResources} is derived from the edge set, so filtering afterwards
     * would leave every visible node advertising its hidden neighbours by {@code externalId} — the
     * denied nodes would be gone from {@code nodes} and still named in the response.
     *
     * <p>Nodes are collected first and relationships second so an edge is judged against the whole
     * visible set rather than the part seen so far.
     */
    public static ResourceNetwork from(List<Record> records, Set<Label> labels, GraphReadScope scope){
        var resourceNetwork = new ResourceNetwork(new HashSet<>(), new HashSet<>(), labels);
        Set<Long> visibleIds = new HashSet<>();

        for(Record record : records){
            Value nodes = record.get("nodes");
            if(!nodes.isNull()){
                for(Value node : nodes.values()){
                    NodeModel model = NodeReadMapper.fromGraphNode(node.asNode());
                    if(!scope.permits(model.getDataSetId())){
                        continue;
                    }
                    resourceNetwork.nodes.add(model);
                    if(model.getId() != null){
                        visibleIds.add(model.getId());
                    }
                }
            }
        }

        for(Record record : records){
            Value relationships = record.get("relationships");
            if(!relationships.isNull()){
                for(Value relationship : relationships.values()){
                    EdgeProxy edge = EdgeProxyTransformer.from(relationship.asRelationship());
                    // A null endpoint cannot be shown to be visible, so it is dropped with the rest.
                    if(!visibleIds.contains(edge.getStart()) || !visibleIds.contains(edge.getEnd())){
                        continue;
                    }
                    resourceNetwork.edges.add(edge);
                }
            }
        }

        attachRelatedResources(resourceNetwork);
        return resourceNetwork;
    }

    /**
     * Populate each node's unified {@code relatedResources} from the network's own edge set (0 extra
     * queries): the neighbours' externalIds come from the nodes already in hand.
     */
    private static void attachRelatedResources(ResourceNetwork network) {
        Map<Long, String> externalIdById = new HashMap<>();
        for (NodeModel r : network.nodes) {
            if (r.getId() != null) {
                externalIdById.put(r.getId(), r.getExternalId());
            }
        }
        Map<Long, List<RelatedNode>> byNode =
                RelatedNodeResolver.fromEdges(externalIdById.keySet(), network.edges, externalIdById);
        for (NodeModel r : network.nodes) {
            if (r.getId() != null) {
                r.setRelatedResources(byNode.getOrDefault(r.getId(), new ArrayList<>()));
            }
        }
    }
}
