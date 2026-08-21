// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.neo4j.driver.Values.parameters;

/**
 * Verifies the graph writes are idempotent against a real Neo4j: a redelivered / retried CREATE
 * (the at-least-once path — ackTimeout, reconnect, in-place retry) must MERGE onto the existing
 * node/edge instead of duplicating it. Requires a container runtime; run via
 * {@code ./gradlew :datahub-stateful-consumer:integrationTest}.
 */
@Tag("integration")
@Testcontainers
class GraphEventNeo4jListenerIT {

    private static final String TENANT = "tenant-1";
    private static final String PASSWORD = "verysecret";

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(DockerImageName.parse("neo4j:5"))
            .withAdminPassword(PASSWORD);

    static Driver driver;
    GraphEventNeo4jListener listener;

    @BeforeAll
    static void openDriver() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", PASSWORD));
    }

    @AfterAll
    static void closeDriver() {
        if (driver != null) driver.close();
    }

    @BeforeEach
    void setUp() {
        Neo4j neo4jConfig = mock(Neo4j.class);
        // Each getSession() hands back a fresh session from the container driver, regardless of tenant.
        when(neo4jConfig.getSession(anyString())).thenAnswer(inv -> driver.session());
        listener = new GraphEventNeo4jListener(neo4jConfig);
        try (Session s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    @Test
    void createResourceIsIdempotentUnderRedelivery() {
        ResourceCudMessage msg = createMessage(List.of(resource(1L, "res_1", "ASSET")), List.of());

        listener.process(msg);
        listener.process(msg); // redelivery / in-place retry after a partial failure

        assertEquals(1L, countNodes(1L), "MERGE must not duplicate the node on redelivery");
    }

    @Test
    void createRelationIsIdempotentUnderRedelivery() {
        ResourceCudMessage msg = createMessage(
                List.of(resource(1L, "res_1", "ASSET"), resource(2L, "res_2", "ASSET")),
                List.of(new EdgeProxy(10L, 1L, 2L, "CONNECTED_TO", 1L, Map.of())));

        listener.process(msg);
        listener.process(msg);

        assertEquals(1L, countNodes(1L));
        assertEquals(1L, countNodes(2L));
        assertEquals(1L, countRelationships(10L), "MERGE must not duplicate the edge on redelivery");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Resource resource(long id, String externalId, String... labels) {
        Resource r = new Resource();
        r.setId(id);
        r.setExternalId(externalId);
        r.setName("Resource " + id);
        r.setLabels(new ArrayList<>(List.of(labels)));
        return r;
    }

    private static ResourceCudMessage createMessage(List<Resource> resources, List<EdgeProxy> edges) {
        ResourceCudMessage msg = new ResourceCudMessage(
                EventAction.CREATE, EventObject.RESOURCE_AND_RELATION, TENANT);
        msg.setResources(resources);
        msg.setEdges(edges);
        return msg;
    }

    private long countNodes(long id) {
        try (Session s = driver.session()) {
            return s.run("MATCH (n {id:$id}) RETURN count(n) AS c", parameters("id", id))
                    .single().get("c").asLong();
        }
    }

    private long countRelationships(long id) {
        try (Session s = driver.session()) {
            return s.run("MATCH ()-[r {id:$id}]->() RETURN count(r) AS c", parameters("id", id))
                    .single().get("c").asLong();
        }
    }
}
