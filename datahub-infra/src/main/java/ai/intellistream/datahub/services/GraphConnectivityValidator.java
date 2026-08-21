// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a deletion would <em>disjoint</em> any surviving node from the graph's root.
 *
 * <p>Pure, stateless graph logic — no Neo4j, no Spring — so it is cheaply unit-testable. The caller
 * supplies the affected connected component(s) (as loaded from Neo4j by
 * {@link Neo4JService#fetchComponentForNodes}) together with the ids of the nodes and edges about
 * to be removed.
 *
 * <p>The rule (undirected connectivity, "any root"): after the deletion, every <em>surviving</em>
 * node must remain connected — ignoring edge direction — to at least one {@code isRoot} node that
 * is not itself being deleted. When a component has no surviving root at all, the fallback is that
 * the deletion must not split it: the survivors must remain a single connected piece. A node that
 * fails this is "stranded"; a delete that strands nothing is allowed, and a delete that would
 * strand nodes is only valid if those nodes are themselves part of the deletion (in which case they
 * are not survivors and never appear here).
 */
public final class GraphConnectivityValidator {

    private GraphConnectivityValidator() {
    }

    /**
     * @param component       the connected component(s) touched by the deletion (every node plus
     *                        every relationship between them)
     * @param deletedNodeIds  ids of nodes being deleted
     * @param deletedEdgeIds  ids of edges being deleted (includes both explicit edge deletes and
     *                        edges incident to deleted nodes)
     * @return the ids of surviving nodes that would be left disconnected from a root; empty means
     *         the deletion preserves connectivity and is allowed
     */
    public static Set<Long> findStrandedNodes(ResourceNetwork component,
                                              Set<Long> deletedNodeIds,
                                              Set<Long> deletedEdgeIds) {
        Set<Long> deletedNodes = deletedNodeIds == null ? Set.of() : deletedNodeIds;
        Set<Long> deletedEdges = deletedEdgeIds == null ? Set.of() : deletedEdgeIds;

        // Index nodes by id and remember which are roots.
        Map<Long, Resource> nodesById = new HashMap<>();
        for (Resource r : component.nodes()) {
            if (r.getId() != null) {
                nodesById.put(r.getId(), r);
            }
        }
        if (nodesById.isEmpty()) {
            return Set.of();
        }

        // Union-find over the *original* graph (all nodes, all edges) to recover the connected
        // components as they stand before the deletion.
        UnionFind uf = new UnionFind(nodesById.keySet());
        for (EdgeProxy e : component.edges()) {
            if (e.getStart() != null && e.getEnd() != null
                    && nodesById.containsKey(e.getStart()) && nodesById.containsKey(e.getEnd())) {
                uf.union(e.getStart(), e.getEnd());
            }
        }

        // Adjacency over the *surviving* graph: drop deleted edges and any edge touching a deleted
        // node (those relationships disappear with the node).
        Map<Long, List<Long>> survivorAdj = new HashMap<>();
        for (Long id : nodesById.keySet()) {
            if (!deletedNodes.contains(id)) {
                survivorAdj.put(id, new ArrayList<>());
            }
        }
        for (EdgeProxy e : component.edges()) {
            Long s = e.getStart();
            Long t = e.getEnd();
            if (s == null || t == null) {
                continue;
            }
            if (deletedEdges.contains(e.getId())) {
                continue;
            }
            if (!survivorAdj.containsKey(s) || !survivorAdj.containsKey(t)) {
                continue;   // one endpoint deleted (or absent) — the edge is gone with it
            }
            survivorAdj.get(s).add(t);
            survivorAdj.get(t).add(s);
        }

        // Group surviving nodes by their original component.
        Map<Long, List<Long>> survivorsByComponent = new HashMap<>();
        for (Long id : nodesById.keySet()) {
            if (deletedNodes.contains(id)) {
                continue;
            }
            survivorsByComponent.computeIfAbsent(uf.find(id), k -> new ArrayList<>()).add(id);
        }

        Set<Long> stranded = new HashSet<>();
        for (List<Long> survivors : survivorsByComponent.values()) {
            // Sources: every surviving root in this component. If there is none, fall back to a
            // single arbitrary survivor — the "no root" case, which just requires the remainder to
            // stay one connected piece.
            List<Long> sources = new ArrayList<>();
            for (Long id : survivors) {
                if (Boolean.TRUE.equals(nodesById.get(id).getIsRoot())) {
                    sources.add(id);
                }
            }
            if (sources.isEmpty()) {
                sources.add(survivors.get(0));
            }

            Set<Long> reached = bfs(sources, survivorAdj);
            for (Long id : survivors) {
                if (!reached.contains(id)) {
                    stranded.add(id);
                }
            }
        }
        return stranded;
    }

    private static Set<Long> bfs(List<Long> sources, Map<Long, List<Long>> adj) {
        Set<Long> reached = new HashSet<>(sources);
        ArrayDeque<Long> queue = new ArrayDeque<>(sources);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            for (Long nb : adj.getOrDefault(cur, Collections.emptyList())) {
                if (reached.add(nb)) {
                    queue.add(nb);
                }
            }
        }
        return reached;
    }

    /** Minimal union-find (disjoint set) over {@code Long} node ids, with path compression. */
    private static final class UnionFind {
        private final Map<Long, Long> parent = new HashMap<>();

        UnionFind(Set<Long> ids) {
            for (Long id : ids) {
                parent.put(id, id);
            }
        }

        Long find(Long x) {
            Long root = x;
            while (!root.equals(parent.get(root))) {
                root = parent.get(root);
            }
            Long cur = x;
            while (!cur.equals(root)) {
                Long next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        void union(Long a, Long b) {
            Long ra = find(a);
            Long rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }
    }
}
