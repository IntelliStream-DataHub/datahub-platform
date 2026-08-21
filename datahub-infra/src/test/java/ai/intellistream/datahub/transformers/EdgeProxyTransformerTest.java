// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.models.EdgeProxy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeProxyTransformerTest {

    @Test
    void fromEdgeEntityKeepsPostgresMetadataAsIs() {
        RelationshipType type = new RelationshipType();
        type.setId(1L);
        type.setName("RELATES_TO");

        EdgeEntity edge = new EdgeEntity();
        edge.setId(42L);
        edge.setStart(1L);
        edge.setEnd(2L);
        edge.setRelationshipType(type);
        // Postgres edge_metadata rows are plain, unprefixed keys — not "metadata_"-prefixed like the
        // Neo4j relationship properties built by GraphEventNeo4jListener.
        edge.setMetadata(Map.of("unit", "kWh", "source", "manual"));

        EdgeProxy proxy = EdgeProxyTransformer.fromEdgeEntity(edge);

        assertThat(proxy.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of("unit", "kWh", "source", "manual"));
    }

    @Test
    void fromEdgeEntityHandlesNullMetadata() {
        RelationshipType type = new RelationshipType();
        type.setId(1L);
        type.setName("RELATES_TO");

        EdgeEntity edge = new EdgeEntity();
        edge.setId(42L);
        edge.setStart(1L);
        edge.setEnd(2L);
        edge.setRelationshipType(type);
        edge.setMetadata(null);

        EdgeProxy proxy = EdgeProxyTransformer.fromEdgeEntity(edge);

        assertThat(proxy.getMetadata()).isEmpty();
    }
}
