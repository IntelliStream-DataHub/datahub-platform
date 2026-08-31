// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading a node back out of the Neo4j projection.
 *
 * <p>The graph holds a subset of each node's columns, and the DTOs seed defaults in their
 * constructors, so the risk here is publishing a default as though the graph had supplied it. A
 * traversal reaching a time series used to report {@code float32} for a BIGINT series for exactly
 * that reason; it now reads the value type the consumer writes, and reports nothing when the node
 * predates that.
 */
class NodeReadMapperGraphTest {

    private static Node graphNode(List<String> labels, Map<String, Object> properties) {
        Node node = mock(Node.class);
        when(node.labels()).thenReturn(labels);
        when(node.asMap()).thenReturn(properties);
        when(node.get(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            Object v = properties.get(inv.getArgument(0, String.class));
            return v == null ? Values.NULL : Values.value(v);
        });
        return node;
    }

    @Test
    void readsTheValueTypeTheConsumerWrote() {
        NodeModel dto = NodeReadMapper.fromGraphNode(graphNode(
                List.of("TIMESERIES"), Map.of("id", 5L, "externalId", "flow_1", "valueType", "bigint")));

        assertThat(dto).isInstanceOf(Timeseries.class);
        assertThat(((Timeseries) dto).getValueType()).isEqualTo("bigint");
    }

    /** A node written before the consumer carried the field must report absent, not the default. */
    @Test
    void reportsNoValueTypeWhenTheNodeHasNone() {
        NodeModel dto = NodeReadMapper.fromGraphNode(graphNode(
                List.of("TIMESERIES"), Map.of("id", 5L, "externalId", "flow_1")));

        assertThat(((Timeseries) dto).getValueType()).isNull();
    }

    /**
     * A deactivated policy must not read back as active.
     *
     * <p>{@code isDeactivated} gates whether a policy is enforced, and the DTO's field is a
     * primitive that defaults to false. Not reading it would report every graph-sourced policy as
     * live, which is the same failure mode as a BIGINT series reading back as float32: a
     * constructor default published as though the graph had said it.
     */
    @Test
    void readsWhetherAPolicyIsDeactivated() {
        var off = (ai.intellistream.datahub.models.Policy) NodeReadMapper.fromGraphNode(graphNode(
                List.of("POLICY"), Map.of("id", 5L, "externalId", "naming_1", "isDeactivated", true)));
        var on = (ai.intellistream.datahub.models.Policy) NodeReadMapper.fromGraphNode(graphNode(
                List.of("POLICY"), Map.of("id", 6L, "externalId", "naming_2", "isDeactivated", false)));

        assertThat(off.isDeactivated()).isTrue();
        assertThat(on.isDeactivated()).isFalse();
    }

    /**
     * Metadata survives the round trip, with the prefix stripped.
     *
     * <p>The graph has no nested maps, so metadata is flattened to one {@code metadata_<key>}
     * property per entry. Nothing put it back on the node path: every graph-sourced node came back
     * with empty metadata, while the edge path reconstructed its own correctly the whole time.
     */
    @Test
    void readsMetadataBackWithoutItsPrefix() {
        NodeModel dto = NodeReadMapper.fromGraphNode(graphNode(
                List.of("ASSET"), Map.of("id", 5L, "externalId", "pump_1",
                        "metadata_vendor", "acme", "metadata_work_order", "wo-12")));

        assertThat(dto.getMetadata())
                .containsEntry("vendor", "acme")
                .containsEntry("work_order", "wo-12");
    }

    /** Structural properties sit beside the metadata ones; only the prefixed keys are metadata. */
    @Test
    void doesNotMistakeStructuralPropertiesForMetadata() {
        NodeModel dto = NodeReadMapper.fromGraphNode(graphNode(
                List.of("ASSET"), Map.of("id", 5L, "externalId", "pump_1", "name", "Pump 1",
                        "source", "SAP", "metadata_vendor", "acme")));

        assertThat(dto.getMetadata()).containsOnlyKeys("vendor");
    }

    /** A node with no metadata gets an empty map, not null: callers iterate it. */
    @Test
    void givesAnEmptyMapWhenTheNodeHasNoMetadata() {
        NodeModel dto = NodeReadMapper.fromGraphNode(graphNode(
                List.of("ASSET"), Map.of("id", 5L, "externalId", "pump_1")));

        assertThat(dto.getMetadata()).isNotNull().isEmpty();
    }

    /**
     * The projection carries a series' unit and engine, so a graph read must return them.
     *
     * <p>These were cleared while the Pulsar payload carried none. The graph is written from the
     * entity now and {@code GraphNodeProperties} projects all four, so clearing them would throw
     * away something the graph actually said.
     */
    @Test
    void readsTheUnitAndEngineTheProjectionCarries() {
        Timeseries dto = (Timeseries) NodeReadMapper.fromGraphNode(graphNode(
                List.of("TIMESERIES"), Map.of("id", 5L, "externalId", "flow_1",
                        "unit", "kg/hr", "unitExternalId", "mass_flow_rate_kghr",
                        "tableEngine", "MERGETREE")));

        assertThat(dto.getUnit()).isEqualTo("kg/hr");
        assertThat(dto.getUnitExternalId()).isEqualTo("mass_flow_rate_kghr");
        assertThat(dto.getTableEngine()).isEqualTo("MERGETREE");
    }

    /**
     * A node written before a field was projected reports it absent, never defaulted.
     *
     * <p>The constructor seeds {@code tableEngine = MERGETREE}, which would otherwise assert an
     * engine the graph never named — the same class of bug as a BIGINT series reading back as
     * float32.
     */
    @Test
    void defaultsNothingTheNodeDoesNotCarry() {
        Timeseries dto = (Timeseries) NodeReadMapper.fromGraphNode(graphNode(
                List.of("TIMESERIES"), Map.of("id", 5L, "externalId", "flow_1")));

        assertThat(dto.getTableEngine()).isNull();
        assertThat(dto.getUnit()).isNull();
        assertThat(dto.getUnitExternalId()).isNull();
    }
}
