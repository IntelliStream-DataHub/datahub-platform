// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.compute;

import ai.intellistream.datahub.analysis.config.AnalysisApiClientFactory;
import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.datahub.jpa.dto.DatapointAggsDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.analysis.AnalysisResponse;
import ai.intellistream.datahub.models.analysis.AnalysisResult;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.services.ResourceService;
import ai.intellistream.datahub.sdk.services.TimeseriesService;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Orchestration test for the moved {@link AnalysisService}: mocks the SDK (focus/candidate lookups,
 * nearest-N graph, aggregated datapoints) and runs the REAL {@link AnalysisComputer}, asserting the
 * focus is resolved, the lagged candidate is ranked + name-filled, and a candidate with no readable
 * data is skipped.
 */
class AnalysisServiceTest {

    private static final long FOCUS_ID = 1L;
    private static final long CAND_ID = 2L;
    private static final long NODATA_ID = 3L;
    private static final int BUCKET = 60;
    private static final int POINTS = 200;
    private static final int LAG = 5;

    private final ZonedDateTime start = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
    private TimeseriesService tsService;
    private ResourceService resService;
    private AnalysisService service;

    @BeforeEach
    void setUp() {
        AnalysisApiClientFactory clientFactory = mock(AnalysisApiClientFactory.class);
        DatahubClient client = mock(DatahubClient.class);
        tsService = mock(TimeseriesService.class);
        resService = mock(ResourceService.class);
        when(clientFactory.forCurrentUser()).thenReturn(client);
        when(client.timeseries()).thenReturn(tsService);
        when(client.resources()).thenReturn(resService);
        service = new AnalysisService(clientFactory, new AnalysisComputer());
    }

    @Test
    void resolvesFocus_ranksLaggedCandidate_andSkipsNoData() {
        // byIds is called for the focus (id 1) and for the candidates (ids 2,3); answer per requested ids.
        when(tsService.byIds(any())).thenAnswer(inv -> {
            Set<Long> want = ids(inv.getArgument(0));
            List<Timeseries> items = new ArrayList<>();
            if (want.contains(FOCUS_ID)) items.add(ts(FOCUS_ID, "focus", "Focus", "float32"));
            if (want.contains(CAND_ID)) items.add(ts(CAND_ID, "cand", "Cand", "float32"));
            if (want.contains(NODATA_ID)) items.add(ts(NODATA_ID, "cand_nodata", "NoData", "float32"));
            return new DataWrapper<Timeseries>().setItems(items);
        });

        // Nearest-N graph: focus feeds two candidate timeseries.
        ResourceNetwork network = new ResourceNetwork(
                Set.of(resource(FOCUS_ID, "Focus"), resource(CAND_ID, "Cand"), resource(NODATA_ID, "NoData")),
                Set.of(edge(1L, FOCUS_ID, CAND_ID), edge(2L, FOCUS_ID, NODATA_ID)),
                Set.of());
        when(resService.fetchNearest(any())).thenReturn(network);

        // Aggregated datapoints: focus + lagged candidate have data; the no-data candidate is absent.
        DataWrapper<DataCollection<DatapointAggsDTO>> data = new DataWrapper<DataCollection<DatapointAggsDTO>>()
                .setItems(List.of(collection(FOCUS_ID, "focus", sine(0)), collection(CAND_ID, "cand", sine(LAG))));
        when(tsService.retrieveAggregated(any())).thenReturn(data);

        AnalysisResponse resp = service.analyze(form());

        assertThat(resp.getResults()).hasSize(1);
        AnalysisResult top = resp.getResults().get(0);
        assertThat(top.getExternalId()).isEqualTo("cand");
        assertThat(top.getName()).isEqualTo("Cand");            // name filled from the resolved series
        assertThat(top.getRawCorrelation()).isGreaterThan(0.9);
        assertThat(resp.getSkipped()).contains("cand_nodata");  // no readable data -> skipped
        assertThat(resp.getFocus()).isNotNull();
        assertThat(resp.getFocus().getName()).isEqualTo("Focus");
    }

    @Test
    void emptyFocusWindow_returns200WithMessage() {
        when(tsService.byIds(any())).thenAnswer(inv -> {
            Set<Long> want = ids(inv.getArgument(0));
            List<Timeseries> items = new ArrayList<>();
            if (want.contains(FOCUS_ID)) items.add(ts(FOCUS_ID, "focus", "Focus", "float32"));
            return new DataWrapper<Timeseries>().setItems(items);
        });
        when(resService.fetchNearest(any())).thenReturn(
                new ResourceNetwork(Set.of(resource(FOCUS_ID, "Focus")), Set.of(), Set.of()));
        when(tsService.retrieveAggregated(any())).thenReturn(
                new DataWrapper<DataCollection<DatapointAggsDTO>>().setItems(List.of())); // no focus data

        AnalysisResponse resp = service.analyze(form());

        assertThat(resp.getResults()).isEmpty();
        assertThat(resp.getMessage()).isNotBlank();
    }

    // --- helpers ---

    private AnalysisForm form() {
        AnalysisForm form = new AnalysisForm();
        form.setFocusId(FOCUS_ID);
        form.setStart(start);
        form.setEnd(start.plusSeconds((long) POINTS * BUCKET));
        return form;
    }

    private static Set<Long> ids(List<IdCollection> ids) {
        return ids.stream().map(IdCollection::getId).collect(Collectors.toSet());
    }

    private static Timeseries ts(long id, String externalId, String name, String valueType) {
        Timeseries t = new Timeseries();
        t.setId(id);
        t.setExternalId(externalId);
        t.setName(name);
        t.setValueType(valueType);
        return t;
    }

    private static Resource resource(long id, String name) {
        Resource r = new Resource();
        r.setId(id);
        r.setName(name);
        r.setLabels(List.of("TIMESERIES"));
        return r;
    }

    private static EdgeProxy edge(long id, long from, long to) {
        return new EdgeProxy(id, from, to, "FEEDS", null, new HashMap<>());
    }

    private double[] sine(int lag) {
        double[] a = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            a[i] = Math.sin((i - lag) * 0.2);
        }
        return a;
    }

    private DataCollection<DatapointAggsDTO> collection(long id, String externalId, double[] values) {
        DataCollection<DatapointAggsDTO> dc = new DataCollection<>();
        dc.setId(id);
        dc.setExternalId(externalId);
        List<DatapointAggsDTO> dps = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            dps.add(new DatapointAggsDTO(start.plusSeconds((long) i * BUCKET), v, v, v, v));
        }
        dc.setDatapoints(dps);
        return dc;
    }
}
