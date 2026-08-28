// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services.graph;

import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mirrors committed Postgres state into the Neo4j graph for one {@link GraphSyncCommand}.
 *
 * <p>The applier is <em>state-based</em>: it re-reads each node and edge from Postgres and writes
 * what it finds, rather than replaying the change that queued the command. Three properties fall
 * out of that, and they are the reason the outbox can be at-least-once without a reconciliation
 * job:
 *
 * <ul>
 *   <li>Applying twice is applying once. Every write is a MERGE on the stable id followed by a
 *       full property assignment, so a redelivery after a crash is a no-op.</li>
 *   <li>Applying late cannot go backwards. A command can only ever read state at or after the
 *       commit that queued it, so the worst case is that it writes a <em>newer</em> state and the
 *       command behind it re-writes the same values.</li>
 *   <li>An entity deleted before its own upsert drains is simply skipped — the graph never
 *       materialises a node Postgres no longer has.</li>
 * </ul>
 *
 * <p>Property assignment is wholesale ({@code SET n = $props}), not a merge: a metadata key
 * removed in Postgres has to stop existing in the graph, and the old incremental writer had no
 * way to express that. {@link GraphNodeProperties} is therefore the single place that decides
 * what a node carries.
 *
 * <p>Callers must have the tenant's Postgres routing in place — the drainer runs inside
 * {@code TenantContext.runWith} — and are expected to hold the per-tenant drain lock, which is
 * what keeps graph writes for one tenant serialised across api instances.
 */
@Service
public class ResourceGraphApplier {

    private static final Logger log = LoggerFactory.getLogger(ResourceGraphApplier.class);

    private final Neo4j neo4j;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;

    public ResourceGraphApplier(Neo4j neo4j, NodeRepository nodeRepository, EdgeRepository edgeRepository) {
        this.neo4j = neo4j;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /**
     * Applies one command in a single Neo4j transaction: either the whole command lands or none
     * of it does, and the outbox row stays pending so the next drain retries it.
     */
    public void apply(GraphSyncCommand command, String tenantId) {
        if (command.isEmpty()) {
            return;
        }
        // Read Postgres before opening the graph transaction: the loads can fail (or find
        // nothing) without leaving a half-applied graph write behind.
        List<NodeEntity> nodes = command.upsertNodeIds().isEmpty()
                ? List.of()
                : nodeRepository.findAllById(command.upsertNodeIds());
        List<EdgeEntity> edges = command.upsertEdgeIds().isEmpty()
                ? List.of()
                : edgeRepository.findAllByIdIn(command.upsertEdgeIds(), EdgeEntity.class);

        logSkipped(command, nodes, edges);

        try (Session session = neo4j.getSession(tenantId);
             Transaction tx = session.beginTransaction()) {
            try {
                // Deletes first: an update that re-parents an edge queues the delete and the
                // upsert together, and applying the removal first keeps the endpoints clean.
                for (Long edgeId : command.deleteEdgeIds()) {
                    deleteEdge(edgeId, tx);
                }
                for (GraphSyncCommand.NodeRef ref : command.deleteNodes()) {
                    deleteNode(ref, tx);
                }
                for (NodeEntity node : nodes) {
                    upsertNode(node, tx);
                }
                for (EdgeEntity edge : edges) {
                    upsertEdge(edge, tx);
                }
                tx.commit();
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
        }
    }

    /**
     * Writes the node and makes its labels match Postgres exactly. MERGE carries the type-label so
     * the match uses the uniqueness constraint's index instead of scanning every node; nodes with
     * no type-label (plain resources) fall back to an unlabelled match, which is correct but
     * unindexed.
     */
    private void upsertNode(NodeEntity node, Transaction tx) {
        Map<String, Object> props = GraphNodeProperties.of(node);
        List<String> labels = GraphNodeProperties.sanitize(GraphNodeProperties.labelsOf(node));
        // Only a type-label may key the MERGE: it is the one label guaranteed to be on the node
        // already (a user label may be newly added by this very write) and the one the uniqueness
        // constraint indexes.
        String typeLabel = TypeLabels.forEntity(node).orElse(null);

        String merge = typeLabel == null
                ? "MERGE (n {id: $id}) SET n = $props RETURN labels(n) AS labels"
                : "MERGE (n:" + typeLabel + " {id: $id}) SET n = $props RETURN labels(n) AS labels";
        var result = tx.run(merge, Map.of("id", node.getId(), "props", props));
        List<String> current = result.single().get("labels").asList(v -> v.asString());

        Set<String> wanted = new HashSet<>(labels);
        List<String> toAdd = wanted.stream().filter(l -> !current.contains(l)).toList();
        List<String> toRemove = current.stream().filter(l -> !wanted.contains(l)).toList();
        if (!toAdd.isEmpty()) {
            tx.run("MATCH (n {id: $id}) SET n" + joined(toAdd), Map.of("id", node.getId()));
        }
        if (!toRemove.isEmpty()) {
            tx.run("MATCH (n {id: $id}) REMOVE n" + joined(toRemove), Map.of("id", node.getId()));
        }
    }

    /**
     * Writes the edge, first removing any copy of it that no longer matches — an update may have
     * retyped it or moved an endpoint, and a relationship's type cannot be changed in place. The
     * delete-then-MERGE pair is idempotent, unlike the CREATE-then-DELETE it replaces, which
     * duplicated the edge whenever a message was redelivered.
     */
    private void upsertEdge(EdgeEntity edge, Transaction tx) {
        if (edge.getRelationshipType() == null) {
            log.warn("Skipping graph edge {}: no relationship type", edge.getId());
            return;
        }
        String type = edge.getRelationshipType().getName().replace("-", "");
        Map<String, Object> params = Map.of(
                "rid", edge.getId(),
                "start", edge.getStart(),
                "end", edge.getEnd(),
                "type", type,
                "props", GraphNodeProperties.of(edge));

        tx.run("MATCH (a)-[r {id: $rid}]->(b) "
                + "WHERE type(r) <> $type OR a.id <> $start OR b.id <> $end DELETE r", params);
        tx.run("MATCH (a {id: $start}) MATCH (b {id: $end}) "
                + "MERGE (a)-[r:" + type + " {id: $rid}]->(b) SET r = $props", params);
    }

    private void deleteNode(GraphSyncCommand.NodeRef ref, Transaction tx) {
        if (ref.id() != null) {
            tx.run("MATCH (n {id: $id}) DETACH DELETE n", Map.of("id", ref.id()));
        } else if (ref.externalId() != null) {
            tx.run("MATCH (n {externalId: $externalId}) DETACH DELETE n",
                    Map.of("externalId", ref.externalId()));
        } else {
            // Nothing to match on. Skip this entry rather than abandoning the rest of the command.
            log.warn("Skipping graph delete: node reference carries neither id nor externalId");
        }
    }

    private void deleteEdge(Long edgeId, Transaction tx) {
        tx.run("MATCH ()-[r {id: $id}]->() DELETE r", Map.of("id", edgeId));
    }

    /**
     * An id the command asked for but Postgres no longer has. Expected when a delete overtakes an
     * earlier upsert; logged because a persistent stream of these means something queues commands
     * for entities that never existed.
     */
    private void logSkipped(GraphSyncCommand command, List<NodeEntity> nodes, List<EdgeEntity> edges) {
        if (!log.isDebugEnabled()) {
            return;
        }
        Set<Long> foundNodes = nodes.stream().map(NodeEntity::getId).collect(Collectors.toSet());
        Set<Long> foundEdges = edges.stream().map(EdgeEntity::getId).collect(Collectors.toSet());
        List<Long> missing = new ArrayList<>();
        command.upsertNodeIds().stream().filter(id -> !foundNodes.contains(id)).forEach(missing::add);
        command.upsertEdgeIds().stream().filter(id -> !foundEdges.contains(id)).forEach(missing::add);
        if (!missing.isEmpty()) {
            log.debug("Graph sync skipped {} id(s) no longer in Postgres: {}", missing.size(), missing);
        }
    }

    private static String joined(List<String> labels) {
        return ":" + String.join(":", labels);
    }
}
