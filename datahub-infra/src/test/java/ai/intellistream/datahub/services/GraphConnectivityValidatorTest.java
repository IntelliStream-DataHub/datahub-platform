// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link GraphConnectivityValidator} — no Neo4j, no Spring, no Testcontainers.
 * Each test crafts a small {@link ResourceNetwork} in memory and asserts which surviving nodes (if
 * any) the deletion would strand from a root.
 */
class GraphConnectivityValidatorTest {

    // ---- helpers ---------------------------------------------------------------------------

    private static Resource node(long id, boolean isRoot) {
        Resource r = new Resource();
        r.setId(id);
        r.setIsRoot(isRoot);
        return r;
    }

    private static EdgeProxy edge(long id, long start, long end) {
        EdgeProxy e = new EdgeProxy();
        e.setId(id);
        e.setStart(start);
        e.setEnd(end);
        return e;
    }

    private static ResourceNetwork net(List<Resource> nodes, List<EdgeProxy> edges) {
        return new ResourceNetwork(new HashSet<>(nodes), new HashSet<>(edges), new HashSet<>());
    }

    private static Set<Long> stranded(ResourceNetwork net, Set<Long> deletedNodes, Set<Long> deletedEdges) {
        return GraphConnectivityValidator.findStrandedNodes(net, deletedNodes, deletedEdges);
    }

    // ---- node deletes ----------------------------------------------------------------------

    @Test
    void deletingALeafIsAllowed() {
        // root(1) - 2 - 3 - 4
        var net = net(
                List.of(node(1, true), node(2, false), node(3, false), node(4, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3), edge(12, 3, 4)));

        // delete leaf 4 and its incident edge 12
        assertTrue(stranded(net, Set.of(4L), Set.of(12L)).isEmpty());
    }

    @Test
    void deletingABridgeNodeStrandsTheNodesBeyondIt() {
        // root(1) - 2 - 3 - 4 ; deleting 2 cuts off 3 and 4
        var net = net(
                List.of(node(1, true), node(2, false), node(3, false), node(4, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3), edge(12, 3, 4)));

        // delete node 2 + its incident edges 10, 11
        assertEquals(Set.of(3L, 4L), stranded(net, Set.of(2L), Set.of(10L, 11L)));
    }

    @Test
    void deletingABridgeNodeTogetherWithItsStrandedSubtreeIsAllowed() {
        var net = net(
                List.of(node(1, true), node(2, false), node(3, false), node(4, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3), edge(12, 3, 4)));

        // delete 2, 3, 4 together — only root survives, nothing stranded
        assertTrue(stranded(net, Set.of(2L, 3L, 4L), Set.of(10L, 11L, 12L)).isEmpty());
    }

    @Test
    void deletingAnEntireComponentIsAllowed() {
        var net = net(
                List.of(node(1, false), node(2, false), node(3, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3)));

        assertTrue(stranded(net, Set.of(1L, 2L, 3L), Set.of(10L, 11L)).isEmpty());
    }

    // ---- edge-only deletes -----------------------------------------------------------------

    @Test
    void deletingABridgeEdgeStrandsTheFarSide() {
        // root(1) - 2 - 3 - 4 ; cutting edge 2-3 strands 3 and 4
        var net = net(
                List.of(node(1, true), node(2, false), node(3, false), node(4, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3), edge(12, 3, 4)));

        assertEquals(Set.of(3L, 4L), stranded(net, Set.of(), Set.of(11L)));
    }

    @Test
    void deletingANonBridgeEdgeIsAllowed() {
        // triangle: root(1) - 2, 2 - 3, 1 - 3 ; removing 2-3 leaves everything reachable
        var net = net(
                List.of(node(1, true), node(2, false), node(3, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3), edge(12, 1, 3)));

        assertTrue(stranded(net, Set.of(), Set.of(11L)).isEmpty());
    }

    // ---- "any root" semantics --------------------------------------------------------------

    @Test
    void survivorReachableThroughASecondRootIsAllowed() {
        // root(1) - 2 - root(3) ; deleting root 1 still leaves 2 reachable from root 3
        var net = net(
                List.of(node(1, true), node(2, false), node(3, true)),
                List.of(edge(10, 1, 2), edge(11, 2, 3)));

        assertTrue(stranded(net, Set.of(1L), Set.of(10L)).isEmpty());
    }

    // ---- no-root fallback (deletion must not split the component) ---------------------------

    @Test
    void rootlessComponentSplitByANodeDeleteIsRejected() {
        // 1 - 2 - 3, no roots ; deleting 2 splits 1 from 3
        var net = net(
                List.of(node(1, false), node(2, false), node(3, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3)));

        assertEquals(Set.of(3L), stranded(net, Set.of(2L), Set.of(10L, 11L)));
    }

    @Test
    void rootlessComponentLeafDeleteThatKeepsOnePieceIsAllowed() {
        // 1 - 2 - 3, no roots ; deleting leaf 3 keeps 1-2 together
        var net = net(
                List.of(node(1, false), node(2, false), node(3, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3)));

        assertTrue(stranded(net, Set.of(3L), Set.of(11L)).isEmpty());
    }

    @Test
    void deletingTheOnlyRootIsAllowedWhenTheRemainderStaysConnected() {
        // root(1) - 2 - 3 ; deleting the lone root leaves 2-3 as one connected piece
        var net = net(
                List.of(node(1, true), node(2, false), node(3, false)),
                List.of(edge(10, 1, 2), edge(11, 2, 3)));

        assertTrue(stranded(net, Set.of(1L), Set.of(10L)).isEmpty());
    }

    @Test
    void deletingTheOnlyRootIsRejectedWhenTheRemainderSplits() {
        // root(1) wired to 2 and 3 separately (2 and 3 not otherwise connected)
        var net = net(
                List.of(node(1, true), node(2, false), node(3, false)),
                List.of(edge(10, 1, 2), edge(11, 1, 3)));

        // remainder {2,3} falls into two pieces — one of them is stranded
        assertEquals(1, stranded(net, Set.of(1L), Set.of(10L, 11L)).size());
    }

    // ---- multiple components in one delete --------------------------------------------------

    @Test
    void aFailureInOneComponentIsCaughtWhileAnotherStaysValid() {
        // component A: root(1) - 2          (untouched)
        // component B: root(3) - 4 - 5      (deleting bridge 4 strands 5)
        var net = net(
                List.of(node(1, true), node(2, false),
                        node(3, true), node(4, false), node(5, false)),
                List.of(edge(10, 1, 2),
                        edge(11, 3, 4), edge(12, 4, 5)));

        assertEquals(Set.of(5L), stranded(net, Set.of(4L), Set.of(11L, 12L)));
    }

    // ---- degenerate inputs ------------------------------------------------------------------

    @Test
    void emptyComponentStrandsNothing() {
        var net = net(List.of(), List.of());
        assertTrue(stranded(net, Set.of(7L), Set.of()).isEmpty());
    }
}
