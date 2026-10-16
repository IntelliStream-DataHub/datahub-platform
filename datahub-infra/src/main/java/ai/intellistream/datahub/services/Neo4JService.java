// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.asset.GraphReadScope;
import ai.intellistream.datahub.asset.NetworkSink;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.RelatedNode;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.transformers.NodeReadMapper;
import ai.intellistream.datahub.transformers.RelatedNodeResolver;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.neo4j.driver.Values.parameters;

@Service
@Slf4j
@AllArgsConstructor
public class Neo4JService {

    private final Neo4j neo4j;
    private final LabelService labelService;

    /**
     * After the walk, the edges and the full resources are fetched in batches of this many resources,
     * so no single query, and no list of ids sent to Neo4j, grows with the size of the network.
     */
    private static final int CHUNK = 5_000;

    /**
     * Loads the connected resource sub-graph around {@code id} — every reachable node plus every
     * relationship between those nodes (the full induced subgraph, not just shortest paths).
     *
     * <p>Backed by {@code apoc.path.subgraphNodes}, which visits each node once
     * ({@code uniqueness: NODE_GLOBAL}), so the cost is ~O(V+E) over the component rather than the
     * exponential path enumeration of the old variable-length query. POLICY nodes are excluded.
     * Built piece by piece from {@link #streamRelatedNodes}, so even a large network never has to
     * arrive from Neo4j in one go. The result is still one object in memory; the fetch-related
     * endpoint sends the network as it is read instead.
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
     * @param scope             which datasets the caller may see. Reachability does not stop at a
     *                          dataset boundary, so without this the component comes back whole
     *                          however little of it the caller is entitled to
     */
    public ResourceNetwork fetchRelatedNodes(Serializable id, Integer depth,
                                             List<String> relationshipTypes, Integer limit,
                                             List<String> excludedLabels, GraphReadScope scope){
        var network = new ResourceNetwork(new HashSet<>(), new HashSet<>(), networkLabels());
        streamRelatedNodes(id, depth, relationshipTypes, limit, excludedLabels, scope, collectInto(network));
        return network;
    }

    /**
     * The same network as {@link #fetchRelatedNodes}, handed over one piece at a time: each edge, then
     * each resource, goes to {@code sink} as soon as it has been read, and is not kept here. The
     * fetch-related endpoint uses this to send a network to the browser while it is still being read,
     * so a network of any size uses only a little memory.
     *
     * <p>Why: this used to be one {@code apoc.path.subgraphAll} query, which returns the whole network
     * as a single result row. Neo4j's Java driver must receive a row completely before handing it
     * over, and it keeps rows in a small, fixed memory pool. A network of 100,000 resources did not
     * fit, and the failure also broke the Valkey client, which uses the same pool, so every request
     * after it hung until the api was restarted. Now the walk returns one small row per resource, and
     * the edges and full resources are fetched afterwards in batches.
     *
     * <p>Resources the caller may not read are still walked through, so the walk reaches what lies
     * beyond them, but they are not returned, and neither are their edges.
     */
    public void streamRelatedNodes(Serializable id, Integer depth, List<String> relationshipTypes, Integer limit,
                                   List<String> excludedLabels, GraphReadScope scope, NetworkSink sink) {
        String relationshipFilter = relationshipFilter(relationshipTypes);
        // Each excluded label becomes a "-LABEL" denylist term; empty => no label filtering.
        String labelFilter = (excludedLabels == null || excludedLabels.isEmpty())
                ? ""
                : excludedLabels.stream().map(l -> "-" + l).collect(java.util.stream.Collectors.joining("|"));
        String query = """
                MATCH (n {id: $id})
                CALL apoc.path.subgraphNodes(n, {
                    maxLevel: $depth,
                    relationshipFilter: $relationshipFilter,
                    labelFilter: $labelFilter,
                    limit: $limit
                }) YIELD node
                RETURN elementId(node) AS elementId, node.id AS id, node.externalId AS externalId,
                       node.dataSetId AS dataSetId
                """;
        traverse(query, parameters(
                "id", id,
                "depth", (depth == null) ? -1 : depth,
                "relationshipFilter", relationshipFilter,
                "labelFilter", labelFilter,
                "limit", (limit == null) ? -1 : limit), scope, sink);
    }

    /**
     * Does the work for {@link #streamRelatedNodes} and {@link #fetchComponentForNodes}, in three
     * steps, all in one Neo4j read transaction so the three see the same graph even if it changes
     * meanwhile:
     * <ol>
     *   <li>{@code query} walks the graph and returns one small row per resource it reaches (its Neo4j
     *       element id, id, externalId and dataSetId). Resources {@code scope} does not allow are
     *       dropped here.</li>
     *   <li>The edges between the remaining resources are fetched in batches and passed on. An edge is
     *       kept only when both its ends are kept, as the old query did.</li>
     *   <li>The full resources are fetched in batches and passed on, each with its list of neighbours
     *       ({@code relatedResources}). That list comes from the edges, which is why every edge goes
     *       first.</li>
     * </ol>
     * The transaction is opened here rather than through the driver's {@code executeRead}, because
     * {@code executeRead} reruns everything after a failure: with part of the network already sent to
     * the browser, a rerun would send it twice.
     */
    private void traverse(String query, Value parameters, GraphReadScope scope, NetworkSink sink) {
        try (Session session = neo4j.getReadSession(); Transaction tx = session.beginTransaction()) {
            List<String> elementIds = new ArrayList<>();
            Set<String> visible = new HashSet<>();
            Map<Long, String> externalIdById = new HashMap<>();
            // Step 1: the walk. Only small rows come back, and the driver fetches them a thousand at a
            // time, however large the network is.
            Result reached = tx.run(query, parameters);
            while (reached.hasNext()) {
                Record row = reached.next();
                Value dataSetId = row.get("dataSetId");
                if (!scope.permits(dataSetId.isNull() ? null : dataSetId.asLong())) {
                    continue;
                }
                String elementId = row.get("elementId").asString();
                if (visible.add(elementId)) {
                    elementIds.add(elementId);
                }
                Value nodeId = row.get("id");
                if (!nodeId.isNull()) {
                    Value externalId = row.get("externalId");
                    externalIdById.put(nodeId.asLong(), externalId.isNull() ? null : externalId.asString());
                }
            }
            log.debug("{}, {}", query, parameters);

            // Step 2: the edges, a batch of resources at a time. Only each edge's ends and type are kept,
            // for the neighbour lists in step 3, not its metadata. A Set, because two relations of the
            // same type between the same two resources have always been returned as one edge
            // (EdgeProxy counts them as equal); ordering by id makes the one kept the lower id.
            Set<EdgeProxy> structure = new HashSet<>();
            for (List<String> chunk : chunks(elementIds)) {
                Result relationships = tx.run("""
                        UNWIND $elementIds AS elementId
                        MATCH (a)-[r]->(b) WHERE elementId(a) = elementId
                        RETURN r, elementId(b) AS other
                        ORDER BY r.id
                        """, parameters("elementIds", chunk));
                while (relationships.hasNext()) {
                    Record row = relationships.next();
                    if (!visible.contains(row.get("other").asString())) {
                        continue;
                    }
                    EdgeProxy edge = EdgeProxyTransformer.from(row.get("r").asRelationship());
                    if (!externalIdById.containsKey(edge.getStart()) || !externalIdById.containsKey(edge.getEnd())) {
                        continue;
                    }
                    if (structure.add(new EdgeProxy(edge.getId(), edge.getStart(), edge.getEnd(), edge.getType(),
                            edge.getRelationshipTypeId(), Map.of()))) {
                        sink.edge(edge);
                    }
                }
            }

            // Step 3: the full resources, each with its neighbour list.
            Map<Long, List<RelatedNode>> related =
                    RelatedNodeResolver.fromEdges(externalIdById.keySet(), structure, externalIdById);
            for (List<String> chunk : chunks(elementIds)) {
                Result nodes = tx.run("""
                        UNWIND $elementIds AS elementId
                        MATCH (n) WHERE elementId(n) = elementId
                        RETURN n
                        """, parameters("elementIds", chunk));
                while (nodes.hasNext()) {
                    NodeModel node = NodeReadMapper.fromGraphNode(nodes.next().get("n").asNode());
                    if (node.getId() != null) {
                        // Dropped once used, so the memory held shrinks as the resources go out.
                        List<RelatedNode> mine = related.remove(node.getId());
                        node.setRelatedResources(mine != null ? mine : new ArrayList<>());
                    }
                    sink.node(node);
                }
            }
            tx.commit();
        }
    }

    /**
     * Turns "follow only these relation types" into APOC's filter syntax: the types joined with "|",
     * which APOC reads as "or". Empty means follow every type. No direction is given, so relations
     * are followed both ways. Types are stored in upper case (RelationshipType#setName) and APOC
     * compares case-sensitively, so the filter is upper-cased too.
     */
    private static String relationshipFilter(List<String> relationshipTypes) {
        return (relationshipTypes == null || relationshipTypes.isEmpty())
                ? ""
                : relationshipTypes.stream().map(String::toUpperCase).collect(java.util.stream.Collectors.joining("|"));
    }

    private static List<List<String>> chunks(List<String> elementIds) {
        List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < elementIds.size(); from += CHUNK) {
            chunks.add(elementIds.subList(from, Math.min(from + CHUNK, elementIds.size())));
        }
        return chunks;
    }

    /** The list of labels that every network response includes next to its resources. */
    public Set<Label> networkLabels() {
        return new HashSet<>(labelService.list());
    }

    private static NetworkSink collectInto(ResourceNetwork network) {
        return new NetworkSink() {
            @Override
            public void edge(EdgeProxy edge) {
                network.edges().add(edge);
            }

            @Override
            public void node(NodeModel node) {
                network.nodes().add(node);
            }
        };
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
                                                       List<String> relationshipTypes, List<String> excludedLabels,
                                                       GraphReadScope scope){
        String relationshipFilter = relationshipFilter(relationshipTypes);
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
                var rn = ResourceNetwork.from(result.list(), new HashSet<>(labelService.list()), scope);
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
     * <p><b>Eventual consistency:</b> Neo4j is mirrored from the {@code resource_outbox} queue
     * after the writing transaction commits, so this read may lag it. A node or edge created by a
     * transaction still in flight is not mirrored yet, which can make the verdict momentarily
     * inaccurate. The PostgreSQL row lock taken during delete does not extend to Neo4j.
     *
     * @param anchorIds the node ids whose components should be loaded; empty/null returns an empty
     *                  network
     */
    public ResourceNetwork fetchComponentForNodes(Collection<Long> anchorIds){
        if(anchorIds == null || anchorIds.isEmpty()){
            return new ResourceNetwork(new HashSet<>(), new HashSet<>(), new HashSet<>(labelService.list()));
        }

        // Deliberately unscoped, unlike the two traversals above. This answers "would deleting these
        // nodes disjoint a survivor from the root?", and that is a property of the whole graph: hiding
        // the part of the component the caller cannot read would report a still-connected node as
        // orphaned and refuse a legal delete. The result is never returned to a caller — only counted —
        // so nothing leaks. Read the same way as fetchRelatedNodes: this reads the whole network with
        // no limit, so with the old single-row query a delete anywhere in a large network would have
        // broken the api in the same way.
        var network = new ResourceNetwork(new HashSet<>(), new HashSet<>(), networkLabels());
        traverse("""
                MATCH (n) WHERE n.id IN $ids
                WITH collect(n) AS starts
                CALL apoc.path.subgraphNodes(starts, {
                    maxLevel: -1,
                    relationshipFilter: '',
                    labelFilter: '',
                    limit: -1
                }) YIELD node
                RETURN elementId(node) AS elementId, node.id AS id, node.externalId AS externalId,
                       node.dataSetId AS dataSetId
                """, parameters("ids", new ArrayList<>(anchorIds)), GraphReadScope.readEverything(),
                collectInto(network));
        return network;
    }
}
