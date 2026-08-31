package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.internal.InternalNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceTransformerFromNodeTest {

    private static InternalNode node(Map<String, Value> props) {
        return new InternalNode(1L, List.of("ASSET", "PIPE"), props);
    }

    @Test
    void fromNodeStripsMetadataPrefixAndKeepsStructuralPropsOut() {
        Resource resource = ResourceTransformer.fromNode(node(Map.of(
                "id", Values.value(42L),
                "externalId", Values.value("pipe_a1"),
                "name", Values.value("Pipe A1"),
                "isRoot", Values.value(false),
                "metadata_site", Values.value("bergen"),
                "metadata_work_order", Values.value("wo-12")
        )));

        assertThat(resource.getMetadata()).containsExactlyInAnyOrderEntriesOf(
                Map.of("site", "bergen", "work_order", "wo-12"));
        assertThat(resource.getExternalId()).isEqualTo("pipe_a1");
        assertThat(resource.getName()).isEqualTo("Pipe A1");
    }

    @Test
    void fromNodeWithoutMetadataPropsYieldsEmptyMetadata() {
        Resource resource = ResourceTransformer.fromNode(node(Map.of(
                "id", Values.value(42L),
                "externalId", Values.value("pipe_a1"),
                "name", Values.value("Pipe A1")
        )));

        assertThat(resource.getMetadata()).isEmpty();
    }
}
