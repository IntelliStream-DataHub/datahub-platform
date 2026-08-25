// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link Neo4JService#fetchNearestNodesByEndLabel} against a real Neo4j + APOC
 * container. Exercises the exact expandConfig Cypher — breadth-first, {@code NODE_GLOBAL} uniqueness,
 * the {@code >TIMESERIES} end-node filter, and the nearest-N cap that the Analyze tab's "expand" raises.
 *
 * <p>Seeded graph: focus(1) -> dataset(2) -> t1(3), t2(4); and t2(4) -> far(5). So t1 and t2 sit two
 * hops from the focus, far sits three hops (only reachable via t2).
 */
@Tag("integration")
@Testcontainers
class Neo4jNearestTimeseriesIT {

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
            .withoutAuthentication()
            .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
            .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*");

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
    void seed() {
        try (var s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n");
            s.run("""
                    CREATE (f:TIMESERIES  {id: 1, name: 'focus',   externalId: 'f'})
                    CREATE (d:DATASET     {id: 2, name: 'dataset', externalId: 'd'})
                    CREATE (t1:TIMESERIES {id: 3, name: 't1',      externalId: 't1'})
                    CREATE (t2:TIMESERIES {id: 4, name: 't2',      externalId: 't2'})
                    CREATE (far:TIMESERIES{id: 5, name: 'far',     externalId: 'far'})
                    CREATE (f)-[:FEEDS    {id: 100, start: 1, end: 2, typeId: 1}]->(d)
                    CREATE (d)-[:CONTAINS {id: 101, start: 2, end: 3, typeId: 2}]->(t1)
                    CREATE (d)-[:CONTAINS {id: 102, start: 2, end: 4, typeId: 2}]->(t2)
                    CREATE (t2)-[:FEEDS   {id: 103, start: 4, end: 5, typeId: 1}]->(far)
                    """);
        }
        Neo4j neo4j = mock(Neo4j.class);
        when(neo4j.getSession()).thenAnswer(inv -> driver.session());
        LabelService labelService = mock(LabelService.class);
        when(labelService.list()).thenReturn(Collections.emptyList());
        service = new Neo4JService(neo4j, labelService,
                new ai.intellistream.datahub.transformers.NodeReadMapper());
    }

    private Set<Long> nodeIds(ResourceNetwork net) {
        return net.nodes().stream().map(ai.intellistream.datahub.models.NodeModel::getId).collect(Collectors.toSet());
    }

    @Test
    void nearestCapReturnsClosestTimeseriesAndConnectingSubgraph() {
        ResourceNetwork net = service.fetchNearestNodesByEndLabel(
                1L, List.of("TIMESERIES"), 2, List.of(), List.of("POLICY"));

        Set<Long> ids = nodeIds(net);
        assertThat(ids).contains(3L, 4L);       // the two nearest timeseries
        assertThat(ids).contains(2L);           // the connecting dataset (so the per-card path renders)
        assertThat(ids).doesNotContain(5L);     // the farther timeseries is excluded by the cap
        assertThat(net.edges()).isNotEmpty();   // relationships come back for path derivation
    }

    @Test
    void raisingTheLimitReachesFartherTimeseries() {
        ResourceNetwork net = service.fetchNearestNodesByEndLabel(
                1L, List.of("TIMESERIES"), 10, List.of(), List.of("POLICY"));

        assertThat(nodeIds(net)).contains(3L, 4L, 5L); // "expand" now reaches the farther one
    }

    @Test
    void excludedLabelIsNotTraversed() {
        try (var s = driver.session()) {
            // A policy hanging off the dataset must never surface as a candidate node.
            s.run("MATCH (d {id: 2}) CREATE (d)-[:HAS {id: 200, start: 2, end: 6, typeId: 3}]->(:POLICY {id: 6, name: 'p'})");
        }
        ResourceNetwork net = service.fetchNearestNodesByEndLabel(
                1L, List.of("TIMESERIES"), 10, List.of(), List.of("POLICY"));

        assertThat(nodeIds(net)).doesNotContain(6L);
    }

    @Test
    void nearestNStaysFastOnABroadGraph() {
        // Focus -> one hub dataset -> 2000 timeseries (all at depth 2). The BFS + cap should stop after
        // it has `limit` end-nodes, so latency tracks the cap, not the 2000 reachable timeseries.
        try (var s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n");
            s.run("""
                    CREATE (f:TIMESERIES {id: 1, externalId: 'f'})
                    CREATE (hub:DATASET {id: 2, externalId: 'hub'})
                    CREATE (f)-[:FEEDS {id: 999999, start: 1, end: 2, typeId: 1}]->(hub)
                    WITH hub
                    UNWIND range(3, 2002) AS i
                    CREATE (t:TIMESERIES {id: i, externalId: 'ts' + toString(i)})
                    CREATE (hub)-[:CONTAINS {id: 1000000 + i, start: 2, end: i, typeId: 2}]->(t)
                    """);
        }
        service.fetchNearestNodesByEndLabel(1L, List.of("TIMESERIES"), 10, List.of(), List.of("POLICY")); // warm up

        long worst = 0;
        for (int limit : new int[]{10, 50, 200}) {
            long best = Long.MAX_VALUE;
            int returned = 0;
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                ResourceNetwork net = service.fetchNearestNodesByEndLabel(
                        1L, List.of("TIMESERIES"), limit, List.of(), List.of("POLICY"));
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
                returned = net.nodes().size();
            }
            worst = Math.max(worst, best);
            System.out.printf("[perf] nearest-%d over 2000 timeseries: %d ms (best of 5), %d nodes returned%n",
                    limit, best, returned);
        }
        // Very generous guard (env-dependent) — the point is the numbers in the log, not a tight gate.
        assertThat(worst).isLessThan(2000);
    }
}
