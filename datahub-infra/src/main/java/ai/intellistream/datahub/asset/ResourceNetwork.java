// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.asset;

import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelatedNode;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.transformers.RelatedNodeResolver;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ResourceNetwork(Set<Resource> nodes, Set<EdgeProxy> edges, Set<Label> labels) {

    /**
     * Maps the result of {@code apoc.path.subgraphAll}. The procedure returns a single record with
     * two list columns: {@code nodes} (every node in the component) and {@code relationships}
     * (every relationship between those nodes). Both are already de-duplicated by APOC, but we
     * still collect into {@link Set}s to be defensive.
     */
    public static ResourceNetwork from(List<Record> records, Set<Label> labels){
        var resourceNetwork = new ResourceNetwork(new HashSet<>(), new HashSet<>(), labels);
        for(Record record : records){
            Value nodes = record.get("nodes");
            if(!nodes.isNull()){
                for(Value node : nodes.values()){
                    resourceNetwork.nodes.add(ResourceTransformer.fromNode(node.asNode()));
                }
            }

            Value relationships = record.get("relationships");
            if(!relationships.isNull()){
                for(Value relationship : relationships.values()){
                    resourceNetwork.edges.add(EdgeProxyTransformer.from(relationship.asRelationship()));
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
        for (Resource r : network.nodes) {
            if (r.getId() != null) {
                externalIdById.put(r.getId(), r.getExternalId());
            }
        }
        Map<Long, List<RelatedNode>> byNode =
                RelatedNodeResolver.fromEdges(externalIdById.keySet(), network.edges, externalIdById);
        for (Resource r : network.nodes) {
            if (r.getId() != null) {
                r.setRelatedResources(byNode.getOrDefault(r.getId(), new ArrayList<>()));
            }
        }
    }
}
