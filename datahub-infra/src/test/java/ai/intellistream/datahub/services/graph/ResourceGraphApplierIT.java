// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services.graph;

import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The applier against a real Neo4j, with Postgres stubbed by fake repositories.
 *
 * <p>Everything here is a property the outbox depends on: applying twice must be applying once
 * (the queue is at-least-once), an entity's current state must win regardless of which command
 * carried it, and an entity Postgres no longer has must not be recreated.
 */
@Tag("integration")
@Testcontainers
class ResourceGraphApplierIT {

    private static final String TENANT = "tenant-1";
    private static final String PASSWORD = "verysecret";

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(DockerImageName.parse("neo4j:5"))
            .withAdminPassword(PASSWORD);

    static Driver driver;

    private final Map<Long, NodeEntity> nodes = new HashMap<>();
    private final Map<Long, EdgeEntity> edges = new HashMap<>();
    private ResourceGraphApplier applier;
    private Neo4jSchemaInitializer schemaInitializer;

    @BeforeAll
    static void openDriver() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", PASSWORD));
    }

    @AfterAll
    static void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @BeforeEach
    void setUp() {
        nodes.clear();
        edges.clear();
        Neo4j neo4jConfig = mock(Neo4j.class);
        // Every session comes from the one container driver, whatever tenant is asked for.
        when(neo4jConfig.getSession(anyString())).thenAnswer(inv -> driver.session());

        NodeRepository nodeRepository = mock(NodeRepository.class);
        when(nodeRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<NodeEntity> found = new ArrayList<>();
            ids.forEach(id -> {
                NodeEntity node = nodes.get(id);
                if (node != null) {
                    found.add(node);
                }
            });
            return found;
        });
        EdgeRepository edgeRepository = mock(EdgeRepository.class);
        when(edgeRepository.findAllByIdIn(any(), any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(edges::get).filter(Objects::nonNull).toList();
        });

        applier = new ResourceGraphApplier(neo4jConfig, nodeRepository, edgeRepository);
        schemaInitializer = new Neo4jSchemaInitializer(neo4jConfig);
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    @Test
    void applyingTheSameCommandTwiceLeavesOneNode() {
        givenAsset(1L, "asset_1", "Pump A");

        apply(upsertNode(1L));
        apply(upsertNode(1L));

        assertThat(countNodes()).isEqualTo(1);
    }

    @Test
    void aReplayedCommandWritesCurrentStateRatherThanTheStateThatQueuedIt() {
        // The queue is ordered but at-least-once, so a command can be applied after a later change
        // has already committed. Reading Postgres at apply time is what makes that harmless.
        givenAsset(1L, "asset_1", "Old name");
        apply(upsertNode(1L));
        givenAsset(1L, "asset_1", "New name");

        apply(upsertNode(1L));

        assertThat(singleProperty("MATCH (n {id: 1}) RETURN n.name AS v")).isEqualTo("New name");
    }

    @Test
    void aPropertyClearedInPostgresStopsExistingInTheGraph() {
        // The incremental writer this replaces could not express removal: a metadata key deleted in
        // Postgres stayed in the graph forever.
        AssetEntity asset = givenAsset(1L, "asset_1", "Pump A");
        asset.getMetadata().put("vendor", "acme");
        apply(upsertNode(1L));
        assertThat(singleProperty("MATCH (n {id: 1}) RETURN n.metadata_vendor AS v")).isEqualTo("acme");

        asset.getMetadata().clear();
        apply(upsertNode(1L));

        assertThat(singleProperty("MATCH (n {id: 1}) RETURN n.metadata_vendor AS v")).isNull();
    }

    @Test
    void aNodeCarriesItsTypeLabelAndItsUserLabels() {
        givenAsset(1L, "asset_1", "Pump A").setLabels("pump,rotating-equipment");

        apply(upsertNode(1L));

        assertThat(labelsOfNodeOne()).containsExactlyInAnyOrder("ASSET", "pump", "rotatingequipment");
    }

    @Test
    void aLabelRemovedInPostgresIsRemovedFromTheNode() {
        AssetEntity asset = givenAsset(1L, "asset_1", "Pump A");
        asset.setLabels("pump,legacy");
        apply(upsertNode(1L));

        asset.setLabels("pump");
        apply(upsertNode(1L));

        assertThat(labelsOfNodeOne()).containsExactlyInAnyOrder("ASSET", "pump");
    }

    @Test
    void anEntityPostgresNoLongerHasIsNotRecreated() {
        // A delete that overtakes its own upsert must not resurrect the node.
        apply(upsertNode(404L));

        assertThat(countNodes()).isZero();
    }

    @Test
    void applyingAnEdgeTwiceLeavesOneRelationship() {
        givenAsset(1L, "asset_1", "Pump A");
        givenAsset(2L, "asset_2", "Tank B");
        givenEdge(10L, 1L, 2L, "FLOWS_TO");
        apply(new GraphSyncCommand(List.of(1L, 2L), List.of(10L), List.of(), List.of()));

        apply(new GraphSyncCommand(List.of(), List.of(10L), List.of(), List.of()));

        assertThat(countRelationships()).isEqualTo(1);
    }

    @Test
    void movingAnEdgesEndpointLeavesNoStaleCopy() {
        // The writer this replaces created the new relationship and deleted the old one, which
        // duplicated the edge whenever the message was redelivered.
        givenAsset(1L, "asset_1", "Pump A");
        givenAsset(2L, "asset_2", "Tank B");
        givenAsset(3L, "asset_3", "Tank C");
        EdgeEntity edge = givenEdge(10L, 1L, 2L, "FLOWS_TO");
        apply(new GraphSyncCommand(List.of(1L, 2L, 3L), List.of(10L), List.of(), List.of()));

        edge.setEnd(3L);
        apply(new GraphSyncCommand(List.of(), List.of(10L), List.of(), List.of()));
        apply(new GraphSyncCommand(List.of(), List.of(10L), List.of(), List.of()));

        assertThat(countRelationships()).isEqualTo(1);
        assertThat(singleProperty("MATCH ()-[r {id: 10}]->(b) RETURN b.id AS v")).isEqualTo(3L);
    }

    @Test
    void retypingAnEdgeLeavesNoStaleCopy() {
        givenAsset(1L, "asset_1", "Pump A");
        givenAsset(2L, "asset_2", "Tank B");
        EdgeEntity edge = givenEdge(10L, 1L, 2L, "FLOWS_TO");
        apply(new GraphSyncCommand(List.of(1L, 2L), List.of(10L), List.of(), List.of()));

        edge.getRelationshipType().setName("FEEDS");
        apply(new GraphSyncCommand(List.of(), List.of(10L), List.of(), List.of()));

        assertThat(countRelationships()).isEqualTo(1);
        assertThat(singleProperty("MATCH ()-[r {id: 10}]->() RETURN type(r) AS v")).isEqualTo("FEEDS");
    }

    @Test
    void deletingRemovesTheNodeAndItsRelationships() {
        givenAsset(1L, "asset_1", "Pump A");
        givenAsset(2L, "asset_2", "Tank B");
        givenEdge(10L, 1L, 2L, "FLOWS_TO");
        apply(new GraphSyncCommand(List.of(1L, 2L), List.of(10L), List.of(), List.of()));

        apply(new GraphSyncCommand(List.of(), List.of(),
                List.of(new GraphSyncCommand.NodeRef(1L, "asset_1")), List.of()));

        assertThat(countNodes()).isEqualTo(1);
        assertThat(countRelationships()).isZero();
    }

    @Test
    void aDeleteWithNoIdentifiersSkipsThatEntryWithoutDroppingTheRest() {
        // The listener this replaces returned out of the whole loop here, silently abandoning
        // every delete queued behind the unusable one.
        givenAsset(1L, "asset_1", "Pump A");
        givenAsset(2L, "asset_2", "Tank B");
        apply(new GraphSyncCommand(List.of(1L, 2L), List.of(), List.of(), List.of()));

        apply(new GraphSyncCommand(List.of(), List.of(),
                List.of(new GraphSyncCommand.NodeRef(null, null),
                        new GraphSyncCommand.NodeRef(2L, "asset_2")),
                List.of()));

        assertThat(countNodes()).isEqualTo(1);
    }

    @Test
    void whatTheApplierWritesIsWhatTheGraphReadersExpect() {
        // The applier assigns a node's properties wholesale, so a name this mapping forgets is a
        // property that disappears from the graph. These are the readers that would notice:
        // every graph-sourced Resource in the api comes back through ResourceTransformer.fromNode.
        AssetEntity asset = givenAsset(1L, "asset_1", "Pump A");
        asset.setDescription("Main feed pump");
        asset.setSource("SAP");
        asset.setIsRoot(true);
        asset.setGeoLocation("{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}");
        asset.getMetadata().put("vendor", "acme");

        apply(upsertNode(1L));

        try (Session session = driver.session()) {
            Node node = session.run("MATCH (n {id: 1}) RETURN n").single().get("n").asNode();
            Resource read = ResourceTransformer.fromNode(node);

            assertThat(read.getId()).isEqualTo(1L);
            assertThat(read.getExternalId()).isEqualTo("asset_1");
            assertThat(read.getName()).isEqualTo("Pump A");
            assertThat(read.getDescription()).isEqualTo("Main feed pump");
            assertThat(read.getSource()).isEqualTo("SAP");
            assertThat(read.getIsRoot()).isTrue();
            assertThat(read.getLabels()).contains("ASSET");
            assertThat(read.getGeoLocation()).isNotNull();
            assertThat(read.getGeoLocation().pointCoordinates()).containsExactly(10.75, 59.91);
        }
    }

    @Test
    void anEdgeComesBackThroughItsOwnReader() {
        givenAsset(1L, "asset_1", "Pump A");
        givenAsset(2L, "asset_2", "Tank B");
        EdgeEntity edge = givenEdge(10L, 1L, 2L, "FLOWS_TO");
        edge.setDescription("suction line");
        edge.getMetadata().put("size", "DN200");

        apply(new GraphSyncCommand(List.of(1L, 2L), List.of(10L), List.of(), List.of()));

        try (Session session = driver.session()) {
            Relationship rel = session.run("MATCH ()-[r {id: 10}]->() RETURN r")
                    .single().get("r").asRelationship();
            EdgeProxy read = EdgeProxyTransformer.from(rel);

            assertThat(read.getId()).isEqualTo(10L);
            assertThat(read.getStart()).isEqualTo(1L);
            assertThat(read.getEnd()).isEqualTo(2L);
            assertThat(read.getType()).isEqualTo("FLOWS_TO");
            assertThat(read.getRelationshipTypeId()).isEqualTo(1L);
            assertThat(read.getDescription()).isEqualTo("suction line");
            assertThat(read.getMetadata()).containsEntry("size", "DN200");
        }
    }

    @Test
    void constraintsAreCreatedForEveryTypeLabelAndCreatingThemAgainIsHarmless() {
        schemaInitializer.ensureConstraints(TENANT);
        new Neo4jSchemaInitializer(schemaInitializerNeo4j()).ensureConstraints(TENANT);

        try (Session session = driver.session()) {
            List<String> names = session.run("SHOW CONSTRAINTS YIELD name RETURN name")
                    .list(r -> r.get("name").asString());
            assertThat(names).contains("node_id_unique_asset", "node_id_unique_dataset",
                    "node_id_unique_policy", "node_id_unique_timeseries", "node_id_unique_function");
        }
    }

    @Test
    void theConstraintRejectsASecondNodeWithTheSameId() {
        // Nothing but the single-active consumer used to stop this; the constraint is what makes
        // concurrent appliers safe now that the consumer is gone.
        schemaInitializer.ensureConstraints(TENANT);
        givenAsset(1L, "asset_1", "Pump A");
        apply(upsertNode(1L));

        try (Session session = driver.session()) {
            assertThatThrownBy(() -> session.run("CREATE (n:ASSET {id: 1})").consume())
                    .hasMessageContaining("already exists");
        }
    }

    private Neo4j schemaInitializerNeo4j() {
        Neo4j neo4jConfig = mock(Neo4j.class);
        when(neo4jConfig.getSession(anyString())).thenAnswer(inv -> driver.session());
        return neo4jConfig;
    }

    private void apply(GraphSyncCommand command) {
        applier.apply(command, TENANT);
    }

    private static GraphSyncCommand upsertNode(Long id) {
        return new GraphSyncCommand(List.of(id), List.of(), List.of(), List.of());
    }

    private AssetEntity givenAsset(Long id, String externalId, String name) {
        AssetEntity asset = (AssetEntity) nodes.get(id);
        if (asset == null) {
            asset = new AssetEntity();
            asset.setId(id);
            asset.setExternalId(externalId);
            asset.setLabels("asset");
            nodes.put(id, asset);
        }
        asset.setName(name);
        return asset;
    }

    private EdgeEntity givenEdge(Long id, Long start, Long end, String type) {
        EdgeEntity edge = new EdgeEntity();
        edge.setId(id);
        edge.setStart(start);
        edge.setEnd(end);
        RelationshipType relationshipType = new RelationshipType();
        relationshipType.setId(1L);
        relationshipType.setName(type);
        edge.setRelationshipType(relationshipType);
        edges.put(id, edge);
        return edge;
    }

    private long countNodes() {
        try (Session session = driver.session()) {
            return session.run("MATCH (n) RETURN count(n) AS c").single().get("c").asLong();
        }
    }

    private long countRelationships() {
        try (Session session = driver.session()) {
            return session.run("MATCH ()-[r]->() RETURN count(r) AS c").single().get("c").asLong();
        }
    }

    private List<String> labelsOfNodeOne() {
        try (Session session = driver.session()) {
            return session.run("MATCH (n {id: 1}) RETURN labels(n) AS l")
                    .single().get("l").asList(v -> v.asString());
        }
    }

    private Object singleProperty(String cypher) {
        try (Session session = driver.session()) {
            var result = session.run(cypher).list();
            return result.isEmpty() ? null : result.get(0).get("v").asObject();
        }
    }
}
