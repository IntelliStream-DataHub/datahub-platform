// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.config.Neo4j;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static org.neo4j.driver.Values.parameters;

@Service
@Slf4j
@AllArgsConstructor
public class Neo4JService {

    private final Neo4j neo4j;
    private final LabelService labelService;

    /**
     * Loads the connected resource sub-graph around {@code id} — every reachable node plus every
     * relationship between those nodes (the full induced subgraph, not just shortest paths).
     *
     * <p>Backed by {@code apoc.path.subgraphAll}, which visits each node once
     * ({@code uniqueness: NODE_GLOBAL}), so the cost is ~O(V+E) over the component rather than the
     * exponential path enumeration of the old variable-length query. POLICY nodes are excluded.
     *
     * @param id                the starting resource id
     * @param depth             max hops to traverse; {@code -1} (or {@code null}) loads the whole
     *                          connected component, a positive value caps the reach
     * @param relationshipTypes relationship types the traversal may follow; {@code null}/empty
     *                          follows every type in both directions
     * @param limit             max number of nodes to return; {@code -1} (or {@code null}) is
     *                          unbounded. When exceeded, the nearest {@code limit} nodes are kept
     *                          (plus every relationship between them)
     * @param excludedLabels    node labels the traversal must neither pass through nor return;
     *                          {@code null}/empty disables label filtering
     */
    public ResourceNetwork fetchRelatedNodes(Serializable id, Integer depth,
                                             List<String> relationshipTypes, Integer limit,
                                             List<String> excludedLabels){
        // Empty filter => APOC follows every relationship type in both directions. A non-empty
        // list is joined with "|" (APOC's OR separator); the types carry no direction prefix, so
        // the traversal stays undirected like the original query.
        String relationshipFilter = (relationshipTypes == null || relationshipTypes.isEmpty())
                ? ""
                // Relationship types are stored upper-cased on create (RelationshipType#setName),
                // and APOC's relationshipFilter is case-sensitive — upper-case the filter to match.
                : relationshipTypes.stream().map(String::toUpperCase).collect(java.util.stream.Collectors.joining("|"));
        // Each excluded label becomes a "-LABEL" denylist term; empty => no label filtering.
        String labelFilter = (excludedLabels == null || excludedLabels.isEmpty())
                ? ""
                : excludedLabels.stream().map(l -> "-" + l).collect(java.util.stream.Collectors.joining("|"));
        int maxLevel = (depth == null) ? -1 : depth;
        int nodeLimit = (limit == null) ? -1 : limit;

        try (Session session = neo4j.getSession()) {

            String query = """
                MATCH (n {id: $id})
                CALL apoc.path.subgraphAll(n, {
                    maxLevel: $depth,
                    relationshipFilter: $relationshipFilter,
                    labelFilter: $labelFilter,
                    limit: $limit
                }) YIELD nodes, relationships
                RETURN nodes, relationships
            """;
            return session.executeRead( tx -> {
                var result = tx.run(query, parameters(
                        "id", id,
                        "depth", maxLevel,
                        "relationshipFilter", relationshipFilter,
                        "labelFilter", labelFilter,
                        "limit", nodeLimit));
                var records = result.list();
                var rn = ResourceNetwork.from(records, new HashSet<>(labelService.list()));
                log.debug("{}, {}", result.consume().query().text(), result.consume().query().parameters() );
                return rn;
            });

        }
    }

    /**
     * Nearest-first BFS from {@code id} that returns the closest {@code limit} nodes carrying any of
     * {@code endLabels}, together with the connecting subgraph (the paths' nodes + relationships).
     *
     * <p>Backed by {@code apoc.path.expandConfig} with {@code NODE_GLOBAL} uniqueness and a
     * {@code >LABEL} end-node filter, so the breadth-first traversal, the label filter, and the
     * nearest-N cap all run inside Neo4j — the caller just uses whatever subgraph comes back. Traversal
     * continues PAST a matching end node, so a timeseries on the path to a farther one is still returned
     * as its own node.
     */
    public ResourceNetwork fetchNearestNodesByEndLabel(Serializable id, List<String> endLabels, Integer limit,
                                                       List<String> relationshipTypes, List<String> excludedLabels){
        String relationshipFilter = (relationshipTypes == null || relationshipTypes.isEmpty())
                ? ""
                // Relationship types are stored upper-cased on create (RelationshipType#setName),
                // and APOC's relationshipFilter is case-sensitive — upper-case the filter to match.
                : relationshipTypes.stream().map(String::toUpperCase).collect(java.util.stream.Collectors.joining("|"));
        // APOC labelFilter: "-X" denylist terms (pruned) + ">Y" end-node terms (returned; traversal continues).
        List<String> terms = new ArrayList<>();
        if(excludedLabels != null){
            excludedLabels.forEach(l -> terms.add("-" + l));
        }
        if(endLabels != null){
            endLabels.forEach(l -> terms.add(">" + l));
        }
        String labelFilter = String.join("|", terms);
        int nodeLimit = (limit == null) ? -1 : limit;

        try (Session session = neo4j.getSession()) {
            String query = """
                MATCH (n {id: $id})
                CALL apoc.path.expandConfig(n, {
                    relationshipFilter: $relationshipFilter,
                    labelFilter: $labelFilter,
                    uniqueness: 'NODE_GLOBAL',
                    bfs: true,
                    minLevel: 1,
                    maxLevel: -1,
                    limit: $limit
                }) YIELD path
                RETURN apoc.coll.toSet(apoc.coll.flatten(collect(nodes(path))))         AS nodes,
                       apoc.coll.toSet(apoc.coll.flatten(collect(relationships(path)))) AS relationships
            """;
            return session.executeRead( tx -> {
                var result = tx.run(query, parameters(
                        "id", id,
                        "relationshipFilter", relationshipFilter,
                        "labelFilter", labelFilter,
                        "limit", nodeLimit));
                var rn = ResourceNetwork.from(result.list(), new HashSet<>(labelService.list()));
                log.debug("{}, {}", result.consume().query().text(), result.consume().query().parameters());
                return rn;
            });
        }
    }

    
    /**
     * Loads the full connected component(s) around a set of anchor nodes — every node reachable
     * from any anchor plus every relationship between those nodes. Seeded with several anchors at
     * once, the result is the union of their components (anchors in the same component collapse to
     * one; anchors in different components are all returned together).
     *
     * <p>Used by the delete-time connectivity check: the caller removes the deleted nodes/edges
     * from this snapshot in memory and verifies no survivor is left disconnected from a root.
     * Unlike {@link #fetchRelatedNodes}, the traversal is unbounded ({@code maxLevel: -1},
     * {@code limit: -1}) and applies no relationship/label filter — connectivity must see every
     * type and label (including POLICY), otherwise a filtered-out path could hide a real
     * connection and produce a wrong verdict.
     *
     * <p><b>Eventual consistency:</b> Neo4j is updated asynchronously from the Pulsar stream, so
     * this read may lag the committing PostgreSQL transaction. A very recently created node/edge
     * may not yet be mirrored here, which can make the verdict momentarily inaccurate. The
     * PostgreSQL row lock taken during delete does not extend to Neo4j.
     *
     * @param anchorIds the node ids whose components should be loaded; empty/null returns an empty
     *                  network
     */
    public ResourceNetwork fetchComponentForNodes(Collection<Long> anchorIds){
        if(anchorIds == null || anchorIds.isEmpty()){
            return new ResourceNetwork(new HashSet<>(), new HashSet<>(), new HashSet<>(labelService.list()));
        }

        try (Session session = neo4j.getSession()) {

            String query = """
                MATCH (n) WHERE n.id IN $ids
                WITH collect(n) AS starts
                CALL apoc.path.subgraphAll(starts, {
                    maxLevel: -1,
                    relationshipFilter: '',
                    labelFilter: '',
                    limit: -1
                }) YIELD nodes, relationships
                RETURN nodes, relationships
            """;
            return session.executeRead( tx -> {
                var result = tx.run(query, parameters("ids", new ArrayList<>(anchorIds)));
                return ResourceNetwork.from(result.list(), new HashSet<>(labelService.list()));
            });

        }
    }

}
