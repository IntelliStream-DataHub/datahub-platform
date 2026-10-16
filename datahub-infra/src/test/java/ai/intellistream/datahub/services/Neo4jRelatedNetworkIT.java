// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.asset.GraphReadScope;
import ai.intellistream.datahub.asset.NetworkSink;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.neo4j.driver.Values.parameters;

/**
 * {@link Neo4JService#fetchRelatedNodes} and {@link Neo4JService#fetchComponentForNodes} now read a
 * network piece by piece instead of with one {@code apoc.path.subgraphAll} query, whose single result
 * row a large network could not fit into. They must still return exactly what that query returned.
 * Each test runs the old query here and compares every resource, edge and neighbour list
 * ({@code relatedResources}) with the new result.
 */
@Tag("integration")
@Testcontainers
class Neo4jRelatedNetworkIT {

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
            .withoutAuthentication()
            .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
            .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*");

    private static final String OLD_RELATED = """
            MATCH (n {id: $id})
            CALL apoc.path.subgraphAll(n, {
                maxLevel: $depth,
                relationshipFilter: $relationshipFilter,
                labelFilter: $labelFilter,
                limit: $limit
            }) YIELD nodes, relationships
            RETURN nodes, relationships
            """;

    private static final String OLD_COMPONENT = """
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

    private static Driver driver;
    private Neo4JService service;

    @BeforeAll
    static void connect() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl());
    }

    @AfterAll
    static void disconnect() {
        if (driver != null) {
            driver.close();
        }
    }

    @BeforeEach
    void wire() {
        Neo4j neo4j = mock(Neo4j.class);
        when(neo4j.getSession()).thenAnswer(inv -> driver.session());
        when(neo4j.getReadSession()).thenAnswer(inv ->
                driver.session(SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()));
        LabelService labelService = mock(LabelService.class);
        when(labelService.list()).thenReturn(Collections.emptyList());
        service = new Neo4JService(neo4j, labelService);
    }

    private static void seed(String cypher) {
        try (var s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n").consume();
            s.run(cypher).consume();
        }
    }

    private static ResourceNetwork oldRelated(long id, int depth, String relationshipFilter, String labelFilter,
                                              int limit, GraphReadScope scope) {
        try (var s = driver.session()) {
            return s.executeRead(tx -> ResourceNetwork.from(tx.run(OLD_RELATED, parameters(
                    "id", id, "depth", depth, "relationshipFilter", relationshipFilter,
                    "labelFilter", labelFilter, "limit", limit)).list(), new HashSet<>(), scope));
        }
    }

    private static ResourceNetwork oldComponent(Collection<Long> ids) {
        try (var s = driver.session()) {
            return s.executeRead(tx -> ResourceNetwork.from(
                    tx.run(OLD_COMPONENT, parameters("ids", new ArrayList<>(ids))).list(), new HashSet<>(),
                    GraphReadScope.readEverything()));
        }
    }

    /** Everything a caller can see of a resource; its neighbour list is compared ignoring order. */
    private record NodeView(String type, Long id, String externalId, String name, String description, Long dataSetId,
                            List<String> labels, Map<String, String> metadata, int relatedCount, Set<Object> related) {
        static NodeView of(NodeModel n) {
            var related = n.getRelatedResources() == null ? List.of() : n.getRelatedResources();
            return new NodeView(n.getClass().getSimpleName(), n.getId(), n.getExternalId(), n.getName(),
                    n.getDescription(), n.getDataSetId(), n.getLabels(), n.getMetadata(), related.size(),
                    new HashSet<>(related));
        }
    }

    private record EdgeView(Long id, Long start, Long end, String type, Long typeId, String description,
                            Long dataSetId, Map<String, String> metadata) {
        static EdgeView of(EdgeProxy e) {
            return new EdgeView(e.getId(), e.getStart(), e.getEnd(), e.getType(), e.getRelationshipTypeId(),
                    e.getDescription(), e.getDataSetId(), e.getMetadata());
        }
    }

    private static void assertSame(ResourceNetwork actual, ResourceNetwork expected) {
        assertThat(actual.nodes().stream().map(NodeView::of).toList())
                .containsExactlyInAnyOrderElementsOf(expected.nodes().stream().map(NodeView::of).toList());
        assertThat(actual.edges().stream().map(EdgeView::of).toList())
                .containsExactlyInAnyOrderElementsOf(expected.edges().stream().map(EdgeView::of).toList());
        assertThat(actual.nodes()).hasSameSizeAs(expected.nodes());
        assertThat(actual.edges()).hasSameSizeAs(expected.edges());
    }

    private static Set<Long> ids(ResourceNetwork network) {
        return network.nodes().stream().map(NodeModel::getId).collect(Collectors.toSet());
    }

    /**
     * Dataset 10 holds 1, 2, 3 and 6; dataset 20 holds 4 and 5; 7 has no dataset; 8 is a policy in
     * dataset 10. Node 6 is reachable only through 4, so a reader of dataset 10 alone gets it back
     * without the node that leads to it. 3 has a self-loop.
     */
    @Nested
    class SmallGraph {

        @BeforeEach
        void seedSmall() {
            seed("""
                    CREATE (n1:ASSET:SITE {id: 1, externalId: 'site', name: 'Site', dataSetId: 10, isRoot: true,
                                           description: 'the root', metadata_owner: 'ops'})
                    CREATE (n2:ASSET {id: 2, externalId: 'pump', name: 'Pump', dataSetId: 10})
                    CREATE (n3:ASSET {id: 3, externalId: 'tank', name: 'Tank', dataSetId: 10})
                    CREATE (n4:ASSET {id: 4, externalId: 'hidden', name: 'Hidden', dataSetId: 20})
                    CREATE (n5:TIMESERIES {id: 5, externalId: 'level', name: 'Level', dataSetId: 20, unit: 'm'})
                    CREATE (n6:ASSET {id: 6, externalId: 'beyond', name: 'Beyond', dataSetId: 10})
                    CREATE (n7:ASSET {id: 7, externalId: 'orphan', name: 'Orphan'})
                    CREATE (n8:POLICY {id: 8, externalId: 'policy', name: 'Policy', dataSetId: 10})
                    CREATE (n1)-[:CONTAINS {id: 101, start: 1, end: 2, typeId: 1}]->(n2)
                    CREATE (n1)-[:CONTAINS {id: 102, start: 1, end: 3, typeId: 1}]->(n3)
                    CREATE (n2)-[:FLOWS_TO {id: 103, start: 2, end: 3, typeId: 2, metadata_animate__datatype: 'oil',
                                            description: 'main line', dataSetId: 10}]->(n3)
                    CREATE (n3)-[:CONTAINS {id: 104, start: 3, end: 4, typeId: 1}]->(n4)
                    CREATE (n4)-[:FLOWS_TO {id: 105, start: 4, end: 6, typeId: 2}]->(n6)
                    CREATE (n4)-[:CONTAINS {id: 106, start: 4, end: 5, typeId: 1}]->(n5)
                    CREATE (n2)-[:REFERS   {id: 107, start: 2, end: 7, typeId: 3}]->(n7)
                    CREATE (n1)-[:HAS      {id: 108, start: 1, end: 8, typeId: 4}]->(n8)
                    CREATE (n3)-[:FLOWS_TO {id: 109, start: 3, end: 3, typeId: 2}]->(n3)
                    CREATE (n6)-[:REFERS   {id: 111, start: 6, end: 2, typeId: 3}]->(n2)
                    """);
        }

        @Test
        void theWholeComponentMatches() {
            var expected = oldRelated(1, -1, "", "", -1, GraphReadScope.readEverything());
            var actual = service.fetchRelatedNodes(1L, -1, null, -1, null, GraphReadScope.readEverything());

            assertSame(actual, expected);
            assertThat(ids(actual)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
            assertThat(actual.edges()).hasSize(10);
        }

        @Test
        void aRestrictedReaderGetsTheSameNarrowedNetwork() {
            var scope = GraphReadScope.restrictedTo(Set.of(10L));
            var expected = oldRelated(1, -1, "", "", -1, scope);
            var actual = service.fetchRelatedNodes(1L, -1, null, -1, null, scope);

            assertSame(actual, expected);
            // 6 is reached through 4, which is never returned, and neither is any edge touching 4 or 7.
            assertThat(ids(actual)).containsExactlyInAnyOrder(1L, 2L, 3L, 6L, 8L);
            assertThat(actual.edges().stream().map(EdgeProxy::getId))
                    .containsExactlyInAnyOrder(101L, 102L, 103L, 108L, 109L, 111L);
        }

        @Test
        void filtersDepthAndLimitMatch() {
            var everything = GraphReadScope.readEverything();
            assertSame(service.fetchRelatedNodes(1L, -1, List.of("contains"), -1, null, everything),
                    oldRelated(1, -1, "CONTAINS", "", -1, everything));
            assertSame(service.fetchRelatedNodes(1L, -1, null, -1, List.of("POLICY"), everything),
                    oldRelated(1, -1, "", "-POLICY", -1, everything));
            assertSame(service.fetchRelatedNodes(1L, 1, null, -1, null, everything),
                    oldRelated(1, 1, "", "", -1, everything));
            assertSame(service.fetchRelatedNodes(1L, -1, null, 3, null, everything),
                    oldRelated(1, -1, "", "", 3, everything));
            assertSame(service.fetchRelatedNodes(4L, 2, List.of("FLOWS_TO", "REFERS"), -1, null,
                            GraphReadScope.restrictedTo(Set.of(10L))),
                    oldRelated(4, 2, "FLOWS_TO|REFERS", "", -1, GraphReadScope.restrictedTo(Set.of(10L))));
        }

        @Test
        void anUnknownStartReturnsNothing() {
            var actual = service.fetchRelatedNodes(999L, -1, null, -1, null, GraphReadScope.readEverything());

            assertThat(actual.nodes()).isEmpty();
            assertThat(actual.edges()).isEmpty();
        }

        @Test
        void theDeleteCheckComponentMatches() {
            assertSame(service.fetchComponentForNodes(List.of(2L, 5L)), oldComponent(List.of(2L, 5L)));
        }

        @Test
        void everyEdgeArrivesBeforeTheFirstNode() {
            List<String> order = new ArrayList<>();
            service.streamRelatedNodes(1L, -1, null, -1, null, GraphReadScope.readEverything(),
                    new NetworkSink() {
                        @Override
                        public void edge(EdgeProxy edge) {
                            order.add("edge");
                        }

                        @Override
                        public void node(NodeModel node) {
                            order.add("node");
                        }
                    });

            assertThat(order).hasSize(18);
            assertThat(order.subList(0, 10)).containsOnly("edge");
            assertThat(order.subList(10, 18)).containsOnly("node");
        }

        /**
         * Two relations of the same type between the same two resources have always come back as one
         * edge, because EdgeProxy counts them as equal; the old query kept whichever came first. The
         * new reading keeps the one with the lower id, and the neighbour list names the same one.
         */
        @Test
        void parallelEdgesOfOneTypeStayOneEdgeAndTheLowerIdWins() {
            try (var s = driver.session()) {
                s.run("MATCH (a {id: 2}), (b {id: 3}) "
                        + "CREATE (a)-[:FLOWS_TO {id: 99, start: 2, end: 3, typeId: 2}]->(b)").consume();
            }
            var everything = GraphReadScope.readEverything();
            var actual = service.fetchRelatedNodes(1L, -1, null, -1, null, everything);
            var expected = oldRelated(1, -1, "", "", -1, everything);

            assertThat(actual.edges()).hasSameSizeAs(expected.edges());
            assertThat(actual.edges().stream().filter(e -> e.getStart() == 2 && e.getEnd() == 3)
                    .map(EdgeProxy::getId)).containsExactly(99L);
            NodeModel pump = actual.nodes().stream().filter(n -> n.getId() == 2).findFirst().orElseThrow();
            assertThat(pump.getRelatedResources().stream()
                    .filter(r -> "FLOWS_TO".equals(r.getRelationshipType()))
                    .map(r -> r.getEdgeId())).containsExactly(99L);
        }
    }

    /**
     * More resources than one batch (5,000), with a chain of edges running across every batch
     * boundary: a hub, 121 groups and 12,100 leaves.
     */
    @Nested
    class LargerThanAChunk {

        @BeforeEach
        void seedLarge() {
            seed("""
                    CREATE (hub:ASSET {id: 2000000, externalId: 'hub', name: 'Hub', dataSetId: 10})
                    WITH hub
                    UNWIND range(0, 120) AS g
                    CREATE (group:ASSET {id: 2001000 + g, externalId: 'g' + g, name: 'Group ' + g, dataSetId: 10})
                    CREATE (hub)-[:CONTAINS {id: 3000000 + g, start: 2000000, end: 2001000 + g, typeId: 1}]->(group)
                    WITH group, g
                    UNWIND range(0, 99) AS k
                    CREATE (leaf:ASSET {id: 2100000 + g * 100 + k, externalId: 'l' + (g * 100 + k),
                                        name: 'Leaf ' + (g * 100 + k), dataSetId: CASE WHEN k % 7 = 0 THEN 20 ELSE 10 END})
                    CREATE (group)-[:CONTAINS {id: 3100000 + g * 100 + k, start: 2001000 + g,
                                               end: 2100000 + g * 100 + k, typeId: 1}]->(leaf)
                    """);
            try (var s = driver.session()) {
                s.run("CREATE INDEX asset_id IF NOT EXISTS FOR (n:ASSET) ON (n.id)").consume();
                s.run("CALL db.awaitIndexes()").consume();
                s.run("""
                        MATCH (a:ASSET) WHERE a.id >= 2100000 AND a.id < 2112099
                        MATCH (b:ASSET {id: a.id + 1})
                        CREATE (a)-[:FLOWS_TO {id: 4000000 + a.id, start: a.id, end: b.id, typeId: 2}]->(b)
                        """).consume();
            }
        }

        @Test
        void everyChunkBoundaryIsCrossed() {
            var everything = GraphReadScope.readEverything();
            var actual = service.fetchRelatedNodes(2000000L, -1, null, -1, null, everything);

            assertSame(actual, oldRelated(2000000, -1, "", "", -1, everything));
            assertThat(actual.nodes()).hasSize(1 + 121 + 12100);
            assertThat(actual.edges()).hasSize(121 + 12100 + 12099);
        }

        @Test
        void aRestrictedReaderAndALimitMatch() {
            var scope = GraphReadScope.restrictedTo(Set.of(10L));
            assertSame(service.fetchRelatedNodes(2000000L, -1, null, -1, null, scope),
                    oldRelated(2000000, -1, "", "", -1, scope));
            assertSame(service.fetchRelatedNodes(2000000L, -1, null, 7000, null, scope),
                    oldRelated(2000000, -1, "", "", 7000, scope));
        }

        @Test
        void theDeleteCheckComponentMatches() {
            assertSame(service.fetchComponentForNodes(List.of(2100500L)), oldComponent(List.of(2100500L)));
        }
    }
}
