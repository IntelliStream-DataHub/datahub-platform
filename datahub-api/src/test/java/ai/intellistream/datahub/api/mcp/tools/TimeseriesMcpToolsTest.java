// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.api.services.UnitService;
import ai.intellistream.datahub.jpa.domains.Unit;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * a {@code ConstraintViolationException} from deep in the service, and offered no way to name a
 * unit from the catalogue at all.
 */
class TimeseriesMcpToolsTest {

    private final TimeseriesService timeseriesService = mock(TimeseriesService.class);
    private final TimeseriesRepository timeseriesRepository = mock(TimeseriesRepository.class);
    private final UnitService unitService = mock(UnitService.class);
    private final TimeseriesMcpTools tools =
            new TimeseriesMcpTools(timeseriesService, timeseriesRepository, unitService);

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
    void unitExternalIdAloneResolvesTheUnitSymbolFromTheCatalogue() throws Exception {
        // The validator only constrains 'unit', so an externalId on its own would still be refused
        // by the service. Resolve it the way the console does instead of failing the call.
        when(unitService.findByExternalId("celsius")).thenReturn(unit("celsius", "Celsius", "°C"));
        when(timeseriesService.save(any())).thenReturn(new DataWrapper<>());

        tools.createTimeseries("reactor_1_temp", "Reactor 1 temperature", 3L, null, null, null, "celsius");

        Timeseries saved = captureSaved();
        assertThat(saved.getUnitExternalId()).isEqualTo("celsius");
        assertThat(saved.getUnit()).isEqualTo("°C");
    }

    @Test
    void aUnitWithoutASymbolFallsBackToItsName() throws Exception {
        when(unitService.findByExternalId("count")).thenReturn(unit("count", "Count", null));
        when(timeseriesService.save(any())).thenReturn(new DataWrapper<>());

        tools.createTimeseries("cycles", "Cycles", 3L, null, null, null, "count");

        assertThat(captureSaved().getUnit()).isEqualTo("Count");
    }

    @Test
    void anExplicitUnitSurvivesTheLookup() throws Exception {
        when(unitService.findByExternalId("celsius")).thenReturn(unit("celsius", "Celsius", "°C"));
        when(timeseriesService.save(any())).thenReturn(new DataWrapper<>());

        tools.createTimeseries("reactor_1_temp", "Reactor 1 temperature", 3L, null, null, "degC", "celsius");

        assertThat(captureSaved().getUnit()).isEqualTo("degC");
    }

    @Test
    void anUnknownUnitExternalIdIsRefusedBeforeTheSave() {
        when(unitService.findByExternalId("kelvins_ish")).thenReturn(null);

        assertThatThrownBy(() -> tools.createTimeseries(
                "reactor_1_temp", "Reactor 1 temperature", 3L, null, null, null, "kelvins_ish"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit_list");

        verifyNoInteractions(timeseriesService);
    }

    private static Unit unit(String externalId, String name, String symbol) {
        Unit u = new Unit();
        u.setExternalId(externalId);
        u.setName(name);
        u.setSymbol(symbol);
        return u;
    }
}
