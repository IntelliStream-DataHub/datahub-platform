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

    /** The graph does not store this, so it must not come back as a constructor default. */
    @Test
    void clearsTheFieldsTheGraphDoesNotStore() {
        Timeseries dto = (Timeseries) NodeReadMapper.fromGraphNode(graphNode(
                List.of("TIMESERIES"), Map.of("id", 5L, "externalId", "flow_1")));

        assertThat(dto.getTableEngine()).isNull();
    }
}
