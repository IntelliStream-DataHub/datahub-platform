// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A timeseries reads back with the labels it actually carries.
 *
 * <p>{@link TimeseriesTransformer} never called {@code setLabels}, so every value a caller saw came
 * from the {@code Timeseries} constructor seeding its type-label — the domain labels were stored on
 * the row, matched by {@code /timeseries/filter}, and then dropped from every response. The gap was
 * invisible because the answer was never empty: it was always exactly {@code ["TIMESERIES"]}, which
 * reads like a real value rather than a missing one.
 *
 * <p>{@code NodeReadMapper} already mapped labels uniformly and says so in its javadoc, but
 * {@code TimeseriesService.get/byIds/filter/list/create/update} all call this transformer directly
 * and bypassed it.
 *
 * <p>Distinct from {@link ResourceTransformerTimeseriesLabelTest}, which pins the same labels
 * reaching Neo4j through {@code ResourceTransformer}. Two paths, two tests.
 */
class TimeseriesTransformerLabelTest {

    private static TimeseriesEntity entity(String labels) {
        TimeseriesEntity entity = new TimeseriesEntity();
        entity.setId(5L);
        entity.setExternalId("reactor_1_rpm");
        entity.setName("Reactor 1 RPM");
        entity.setValueType("float32"); // so the value-type branch doesn't warn its way to BIGINT
        entity.setLabels(labels);
        return entity;
    }

    @Test
    @DisplayName("domain labels on the row reach the response")
    void domainLabelsAreReturned() {
        Timeseries dto = TimeseriesTransformer.from(entity("TIMESERIES,ROTATING_EQUIPMENT,CRITICAL"));

        assertThat(dto.getLabels())
                .containsExactlyInAnyOrder("TIMESERIES", "ROTATING_EQUIPMENT", "CRITICAL");
    }

    /**
     * {@code NodeModel.setLabels} appends the type-label when the column does not carry it, so the
     * type is guaranteed however the row was written.
     */
    @Test
    @DisplayName("the type-label survives a row whose labels column lacks it")
    void theTypeLabelIsAlwaysPresent() {
        assertThat(TimeseriesTransformer.from(entity("ROTATING_EQUIPMENT")).getLabels())
                .contains("TIMESERIES", "ROTATING_EQUIPMENT");

        assertThat(TimeseriesTransformer.from(entity(null)).getLabels())
                .containsExactly("TIMESERIES");

        assertThat(TimeseriesTransformer.from(entity("")).getLabels())
                .containsExactly("TIMESERIES");
    }

    @Test
    @DisplayName("the collection overload maps labels too, not just the single read")
    void theCollectionOverloadMapsLabels() {
        List<Timeseries> mapped =
                List.copyOf(TimeseriesTransformer.from(List.of(entity("TIMESERIES,CRITICAL"))));

        assertThat(mapped).hasSize(1);
        assertThat(mapped.getFirst().getLabels()).contains("CRITICAL");
    }
}
