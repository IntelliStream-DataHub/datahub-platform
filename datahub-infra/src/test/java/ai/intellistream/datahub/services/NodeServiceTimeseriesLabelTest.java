// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * A created timeseries carries its {@code "TIMESERIES"} type-label, and — since the label mapping was
 * fixed — any additional labels the caller sent alongside it.
 *
 * <p>{@code mapNewNodeFromTimeseries} used to build a single hardcoded {@code TIMESERIES} LabelForm,
 * so a create carrying domain labels had them silently dropped; timeseries was the one node type that
 * could not be labelled on create. It now resolves the DTO's own label list, which
 * {@code NodeModel.setLabels} guarantees already contains the type-label.
 *
 * <p>Because {@code mapTimeseriesFrom} maps the DTO through its inherited getters, this doubles as a
 * guard that the {@code NodeModel} shape still maps DTO → entity (failures there are swallowed to
 * {@code null}).
 */
@ExtendWith(MockitoExtension.class)
class NodeServiceTimeseriesLabelTest {

    @Mock private LabelService labelService;
    @Mock private DataSetRepository dataSetRepository;
    @InjectMocks private NodeService nodeService;

    /** Echo the requested names back as Label rows, so the assertions read the real resolution path. */
    private void echoLabels() {
        when(labelService.findAllAndCreateFromNames(anyList())).thenAnswer(inv -> {
            List<String> names = inv.getArgument(0);
            return names.stream().map(n -> {
                var l = new Label();
                l.setName(n);
                return l;
            }).toList();
        });
    }

    @Test
    void mapTimeseriesFromStampsTheTimeseriesTypeLabel() {
        echoLabels();

        Timeseries ts = new Timeseries();
        ts.setExternalId("rpm");
        ts.setName("RPM Sensor");
        ts.setUnit("hz");

        List<TimeseriesEntity> entities = List.copyOf(nodeService.mapTimeseriesFrom(List.of(ts)));

        assertThat(entities).hasSize(1);
        TimeseriesEntity entity = entities.get(0);
        assertThat(entity).as("null means mapNewNodeFromTimeseries swallowed an exception").isNotNull();
        assertThat(entity.getLabels()).isEqualTo("TIMESERIES");
        assertThat(entity.getExternalId()).isEqualTo("rpm");
    }

    @Test
    void callerSuppliedLabelsSurviveAlongsideTheTypeLabel() {
        echoLabels();

        Timeseries ts = new Timeseries();
        ts.setExternalId("rpm");
        ts.setName("RPM Sensor");
        ts.setUnit("hz");
        ts.setLabels(List.of("REACTOR", "CRITICAL"));

        List<TimeseriesEntity> entities = List.copyOf(nodeService.mapTimeseriesFrom(List.of(ts)));

        TimeseriesEntity entity = entities.get(0);
        assertThat(entity).isNotNull();
        // The caller's labels are kept and the type-label is still applied — it used to be the only
        // label that survived.
        assertThat(entity.getLabels().split(","))
                .containsExactlyInAnyOrder("REACTOR", "CRITICAL", "TIMESERIES");
    }
}
