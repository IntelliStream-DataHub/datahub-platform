// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code timeseries_create} tool's unit rule. {@link Timeseries#getUnit()} is
 * {@code @NotBlank} and {@code TimeseriesService.save()} runs the validator itself, so a create
 * without a unit fails whatever the caller sends — the tool used to let the model discover that as
 * a {@code ConstraintViolationException} from deep in the service. Resolving a
 * {@code unitExternalId} against the catalogue is the service's job, covered in
 * {@code TimeseriesServiceTest}.
 *
 * <p>Also covers {@code timeseries_list}'s route to its data. That tool read
 * {@code TimeseriesRepository.list(cap)} — the unrestricted overload — while
 * {@code GET /timeseries} went through {@link TimeseriesService#readList(int)}, which narrows to
 * the caller's readable datasets. {@code ROLE_DATAHUB_ACCESS} on the filter chain is
 * authentication, not a dataset grant, so the tool returned every series in the tenant to a caller
 * holding no grants at all.
 */
class TimeseriesMcpToolsTest {

    private final TimeseriesService timeseriesService = mock(TimeseriesService.class);
    private final TimeseriesMcpTools tools = new TimeseriesMcpTools(timeseriesService);

    private Timeseries captureSaved() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DataWrapper<Timeseries>> captor = ArgumentCaptor.forClass(DataWrapper.class);
        verify(timeseriesService).save(captor.capture());
        return captor.getValue().getItems().iterator().next();
    }

    @Test
    void refusesATimeseriesWithNeitherUnitNorUnitExternalId() {
        assertThatThrownBy(() -> tools.createTimeseries(
                "reactor_1_temp", "Reactor 1 temperature", 3L, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitExternalId");

        verifyNoInteractions(timeseriesService);
    }

    @Test
    void treatsABlankUnitAsNoUnitAtAll() {
        assertThatThrownBy(() -> tools.createTimeseries(
                "reactor_1_temp", "Reactor 1 temperature", 3L, null, null, "  ", ""))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(timeseriesService);
    }

    @Test
    void unitAloneIsEnough() throws Exception {
        when(timeseriesService.save(any())).thenReturn(new DataWrapper<>());

        tools.createTimeseries("reactor_1_temp", "Reactor 1 temperature", 3L, null, null, "Celsius", null);

        assertThat(captureSaved().getUnit()).isEqualTo("Celsius");
    }

    @Test
    void unitExternalIdAloneIsPassedOnForTheServiceToResolve() throws Exception {
        when(timeseriesService.save(any())).thenReturn(new DataWrapper<>());

        tools.createTimeseries("reactor_1_temp", "Reactor 1 temperature", 3L, null, null, null, "celsius");

        Timeseries saved = captureSaved();
        assertThat(saved.getUnitExternalId()).isEqualTo("celsius");
        assertThat(saved.getUnit()).isNull();
    }

    @Test
    void listGoesThroughTheServiceSoTheDatasetAclApplies() {
        when(timeseriesService.readList(100)).thenReturn(List.of());

        tools.listTimeseries(null);

        // The point of the test is the route, not the payload: readList() is the only listing path
        // that intersects the caller's readable datasets.
        verify(timeseriesService).readList(100);
    }

    @Test
    void listPassesTheCallersLimitThroughToTheNarrowedRead() {
        when(timeseriesService.readList(25)).thenReturn(List.of());

        tools.listTimeseries(25);

        verify(timeseriesService).readList(25);
    }
}
