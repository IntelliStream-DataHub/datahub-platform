// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.asset;

import ai.intellistream.datahub.models.NodeModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dataset scoping of a graph traversal.
 *
 * <p>Reachability does not stop at a dataset boundary, so the component Neo4j returns is whatever
 * is connected — the caller's grants have no say in it. Gating only the node the traversal starts
 * from, which is what {@code /resources/export}, {@code /fetch-related} and {@code /fetch-nearest}
 * did, meant one read grant plus one edge into a neighbouring dataset was enough to pull that
 * dataset back: for the export, every node in the component with its full metadata, as a file.
 *
 * <p>The subtle half is {@code relatedResources}. It is derived from the edge set after the nodes
 * are mapped, so filtering nodes without filtering edges — or filtering after the derivation — puts
 * the hidden neighbours' externalIds back on the visible nodes. Hiding a node means hiding the
 * edges that touch it.
 */
class ResourceNetworkScopeTest {

    private static final long READABLE = 10L;
    private static final long DENIED = 99L;

    // ---- fakes ---------------------------------------------------------------------------------

    private static Node node(long id, String externalId, Long dataSetId) {
        Map<String, Object> props = new HashMap<>();
        props.put("id", id);
        props.put("externalId", externalId);
        props.put("name", externalId);
        props.put("dataSetId", dataSetId); // deliberately nullable: that is the orphan case
        Node n = mock(Node.class);
        when(n.labels()).thenReturn(List.of("ASSET"));
        when(n.asMap()).thenReturn(props);
        when(n.get(anyString())).thenAnswer(inv -> {
            Object v = props.get(inv.getArgument(0, String.class));
            return v == null ? Values.NULL : Values.value(v);
        });
        return n;
    }

    private static Relationship edge(long id, long start, long end) {
        Relationship r = mock(Relationship.class);
        when(r.asMap()).thenReturn(Map.of("id", id, "start", start, "end", end, "typeId", 7L));
        when(r.elementId()).thenReturn("rel-" + id);
        when(r.type()).thenReturn("RELATES_TO");
        return r;
    }

    /** One record with a {@code nodes} and a {@code relationships} column, as APOC returns. */
    private static Record record(List<Node> nodes, List<Relationship> relationships) {
        // Built before the stubbing starts: listValue() stubs mocks of its own, and Mockito
        // rejects that happening inside an unfinished when(...).thenReturn(...).
        Value nodeList = listValue(nodes, true);
        Value relationshipList = listValue(relationships, false);
        Record rec = mock(Record.class);
        when(rec.get("nodes")).thenReturn(nodeList);
        when(rec.get("relationships")).thenReturn(relationshipList);
        return rec;
    }

    private static Value listValue(List<?> items, boolean asNodes) {
        List<Value> wrapped = new ArrayList<>();
        for (Object item : items) {
            Value v = mock(Value.class);
            if (asNodes) {
                when(v.asNode()).thenReturn((Node) item);
            } else {
                when(v.asRelationship()).thenReturn((Relationship) item);
            }
            wrapped.add(v);
        }
        Value list = mock(Value.class);
        when(list.isNull()).thenReturn(false);
        when(list.values()).thenReturn(wrapped);
        return list;
    }

    /** Two connected nodes, one in a dataset the caller may read and one in a dataset they may not. */
    private static List<Record> twoNodesAcrossADatasetBoundary() {
        return List.of(record(
                Arrays.asList(node(1L, "pump_a", READABLE), node(2L, "secret_b", DENIED)),
                List.of(edge(500L, 1L, 2L))));
    }

    private static List<String> externalIds(ResourceNetwork network) {
        return network.nodes().stream().map(NodeModel::getExternalId).sorted().toList();
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a node in a dataset the caller cannot read is not returned")
    void hidesNodesFromDeniedDatasets() {
        ResourceNetwork network = ResourceNetwork.from(
                twoNodesAcrossADatasetBoundary(), Set.of(), GraphReadScope.restrictedTo(Set.of(READABLE)));

        assertThat(externalIds(network)).containsExactly("pump_a");
    }

    @Test
    @DisplayName("an edge to a hidden node is dropped with it")
    void hidesEdgesTouchingDeniedNodes() {
        ResourceNetwork network = ResourceNetwork.from(
                twoNodesAcrossADatasetBoundary(), Set.of(), GraphReadScope.restrictedTo(Set.of(READABLE)));

        assertThat(network.edges()).isEmpty();
    }

    /**
     * The regression the ordering exists for: filter after {@code attachRelatedResources} and the
     * denied node is gone from {@code nodes} while still named on its visible neighbour.
     */
    @Test
    @DisplayName("a hidden neighbour is not named in the visible node's relatedResources")
    void doesNotLeakDeniedNeighboursThroughRelatedResources() {
        ResourceNetwork network = ResourceNetwork.from(
                twoNodesAcrossADatasetBoundary(), Set.of(), GraphReadScope.restrictedTo(Set.of(READABLE)));

        NodeModel visible = network.nodes().iterator().next();
        assertThat(visible.getExternalId()).isEqualTo("pump_a");
        assertThat(visible.getRelatedResources()).isEmpty();
    }

    @Test
    @DisplayName("an all-datasets reader still gets the whole component")
    void readEverythingIsUnfiltered() {
        ResourceNetwork network = ResourceNetwork.from(
                twoNodesAcrossADatasetBoundary(), Set.of(), GraphReadScope.readEverything());

        assertThat(externalIds(network)).containsExactly("pump_a", "secret_b");
        assertThat(network.edges()).hasSize(1);
    }

    @Test
    @DisplayName("the two-argument overload is unfiltered, for the internal connectivity read")
    void defaultOverloadIsUnfiltered() {
        ResourceNetwork network = ResourceNetwork.from(twoNodesAcrossADatasetBoundary(), Set.of());

        assertThat(externalIds(network)).containsExactly("pump_a", "secret_b");
    }

    /**
     * An orphan node carries no dataset to match a grant against, so only an all-datasets reader
     * may see it — the rule {@code DataSecurity.hasReadPermissionToDataSet} applies everywhere else.
     */
    @Test
    @DisplayName("an orphan node needs read-everything, exactly as it does outside the graph")
    void orphanNodesRequireReadEverything() {
        List<Record> orphan = List.of(record(
                Arrays.asList(node(1L, "pump_a", READABLE), node(3L, "unattached", null)), List.of()));

        assertThat(externalIds(ResourceNetwork.from(orphan, Set.of(),
                GraphReadScope.restrictedTo(Set.of(READABLE))))).containsExactly("pump_a");
        assertThat(externalIds(ResourceNetwork.from(orphan, Set.of(),
                GraphReadScope.readEverything()))).containsExactly("pump_a", "unattached");
    }

    @Test
    @DisplayName("a caller with no grants at all sees nothing, not everything")
    void noGrantsMeansNothing() {
        ResourceNetwork network = ResourceNetwork.from(
                twoNodesAcrossADatasetBoundary(), Set.of(), GraphReadScope.restrictedTo(Set.of()));

        assertThat(network.nodes()).isEmpty();
        assertThat(network.edges()).isEmpty();
    }

    @Test
    @DisplayName("an edge survives only when both of its endpoints do")
    void keepsEdgesBetweenTwoVisibleNodes() {
        List<Record> chain = List.of(record(
                Arrays.asList(node(1L, "pump_a", READABLE), node(2L, "pump_b", READABLE),
                        node(3L, "secret_c", DENIED)),
                List.of(edge(500L, 1L, 2L), edge(501L, 2L, 3L))));

        ResourceNetwork network = ResourceNetwork.from(
                chain, Set.of(), GraphReadScope.restrictedTo(Set.of(READABLE)));

        assertThat(externalIds(network)).containsExactly("pump_a", "pump_b");
        assertThat(network.edges()).hasSize(1);
        assertThat(network.edges().iterator().next().getId()).isEqualTo(500L);
    }
}
