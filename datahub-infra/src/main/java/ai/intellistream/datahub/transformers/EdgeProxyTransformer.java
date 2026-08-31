// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.dto.EdgeDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import org.neo4j.driver.types.Relationship;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EdgeProxyTransformer {

    /** Metadata is persisted on graph relationships with a "metadata_" key prefix (see
     *  GraphEventNeo4jListener), so it can live alongside the structural properties
     *  (id/start/end/typeId/description). */
    private static final String METADATA_PREFIX = "metadata_";

    /** Keep only the "metadata_"-prefixed properties and strip the prefix, so callers see the
     *  user's original metadata keys and not the structural graph properties. */
    private static Map<String,String> stripMetadataPrefix(Map<String,String> props){
        if(props == null){
            return new HashMap<>();
        }
        return props.entrySet().stream()
                .filter(e -> e.getKey().startsWith(METADATA_PREFIX))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(METADATA_PREFIX.length()),
                        Map.Entry::getValue));
    }

    public static EdgeProxy from(Relationship r) {
        Map<String,String> props = r.asMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        props.put("elementId", r.elementId());
        EdgeProxy ep = new EdgeProxy(
                Long.parseLong(props.get("id")),
                Long.parseLong(props.get("start")),
                Long.parseLong(props.get("end")),
                r.type(),
                Long.valueOf(props.get("typeId")),
                stripMetadataPrefix(props)
        );
        ep.setDescription(props.get("description"));
        if (props.get("dataSetId") != null) {
            ep.setDataSetId(Long.valueOf(props.get("dataSetId")));
        }
        return ep;
    }

    public static EdgeProxy fromEdgeEntity(EdgeEntity edge) {
        EdgeProxy e = new EdgeProxy();
        e.setId(edge.getId());
        e.setStart(edge.getStart());
        e.setEnd(edge.getEnd());
        e.setType(edge.getRelationshipType().getName());
        e.setRelationshipTypeId(edge.getRelationshipType().getId());
        // EdgeEntity.metadata is the Postgres edge_metadata table: plain, unprefixed user keys
        // already (the "metadata_" prefix only exists on the Neo4j relationship properties built by
        // GraphEventNeo4jListener). Stripping it here — as if this were Neo4j-sourced like from(Relationship)
        // below — matched nothing and silently emptied every edge's metadata before it reached Neo4j.
        e.setMetadata(edge.getMetadata() != null ? new HashMap<>(edge.getMetadata()) : new HashMap<>());
        e.setDescription(edge.getDescription());
        if (edge.getDataSet() != null) {
            e.setDataSetId(edge.getDataSet().getId());
        }
        return e;
    }

    public static EdgeProxy fromEdgeEntity(EdgeDTO edge) {
        EdgeProxy e = new EdgeProxy();
        e.setId(edge.getId());
        e.setStart(edge.getStart());
        e.setEnd(edge.getEnd());
        return e;
    }

    public static List<EdgeProxy> fromEdgeEntities(Collection<EdgeEntity> edgeEntities){
        return edgeEntities.stream().map(EdgeProxyTransformer::fromEdgeEntity).toList();
    }

    public static List<EdgeProxy> fromEdgeDTOs(Collection<EdgeDTO> edgeEntities){
        return edgeEntities.stream().map(EdgeProxyTransformer::fromEdgeEntity).toList();
    }
}
