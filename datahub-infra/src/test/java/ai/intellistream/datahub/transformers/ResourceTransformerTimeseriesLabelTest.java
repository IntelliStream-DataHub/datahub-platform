// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that a timeseries's labels reach Neo4j. {@code TimeseriesService.save()} builds the graph
 * {@code ResourceCudMessage} from {@code ResourceTransformer.from(entities)} — <em>not</em> from the
 * {@code Timeseries} DTO (which has no labels field) — so the {@code "TIMESERIES"} type-label stamped on the
 * entity must survive the entity → {@link Resource} transform onto that message. Guards the NodeModel
 * re-parent of {@code Timeseries} from silently dropping timeseries labels out of the graph.
 */
class ResourceTransformerTimeseriesLabelTest {

    @Test
    void timeseriesEntityLabelsFlowOntoTheResourceSentToNeo4j() {
        TimeseriesEntity entity = new TimeseriesEntity();
        entity.setId(5L);
        entity.setExternalId("rpm");
        entity.setName("RPM");
        entity.setValueType("float32");     // set so the transform's value-type branch doesn't NPE
        entity.setLabels("TIMESERIES");     // stamped by NodeService.mapTimeseriesFrom on create

        Resource resource = ResourceTransformer.from(entity);

        assertThat(resource.getLabels()).containsExactly("TIMESERIES");
    }
}
