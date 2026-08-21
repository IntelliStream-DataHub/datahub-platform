// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.compute;

import ai.intellistream.datahub.analysis.config.AnalysisApiClientFactory;
import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.datahub.jpa.dto.DatapointAggsDTO;
import ai.intellistream.datahub.models.FetchNearestResourcesForm;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.analysis.AnalysisComputeRequest;
import ai.intellistream.datahub.models.analysis.AnalysisResponse;
import ai.intellistream.datahub.models.analysis.AnalysisResult;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.timeseries.Timeseries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The data-gathering half of the timeseries relationship analysis, now co-located with the compute.
 * It fetches everything it needs from the api over HTTP <em>as the calling user</em> (via the
 * {@link DatahubClient} the {@link AnalysisApiClientFactory} builds with the forwarded JWT), so the
 * api's per-dataset ACLs still apply, then hands the finished collections to the in-process
 * {@link AnalysisComputer}. It knows nothing of the DB, tenants, or the graph store directly.
 */
@Service
@Slf4j
public class AnalysisService {

    /** Target number of buckets the derived width aims for (also the analysis's resolution). */
    private static final int TARGET_BUCKETS = 500;

    /** Nearest-neighbour timeseries cap used when the form omits one. */
    private static final int DEFAULT_LIMIT = 10;

    /** Row cap per series on the fetch — generous, since the derivation stays near TARGET_BUCKETS. */
    private static final int FETCH_LIMIT = 100_000;

    private final AnalysisApiClientFactory clientFactory;
    private final AnalysisComputer computer;

    public AnalysisService(AnalysisApiClientFactory clientFactory, AnalysisComputer computer) {
        this.clientFactory = clientFactory;
        this.computer = computer;
    }

    public AnalysisResponse analyze(AnalysisForm form) {
        if (form.getStart() == null || form.getEnd() == null) {
            throw badRequest("start and end are required");
        }
        if (!form.getEnd().isAfter(form.getStart())) {
            throw badRequest("end must be after start");
        }
        long startSec = form.getStart().toEpochSecond();
        long endSec = form.getEnd().toEpochSecond();
        // Bucket width is DERIVED from the window span (~TARGET_BUCKETS buckets), never a user knob.
        int bucketSeconds = deriveBucketSeconds(startSec, endSec);

        DatahubClient client = clientFactory.forCurrentUser();

        // Resolve focus and require it to be numeric.
        Timeseries focus = resolveFocus(client, form);
        if (focus == null || focus.getId() == null) {
            throw badRequest("focus timeseries not found");
        }
        if (!isNumeric(focus.getValueType())) {
            throw badRequest("focus timeseries is not numeric");
        }

        // BFS from the focus (nearest-N TIMESERIES), read-gated on the focus inside the api.
        int limit = form.getLimit() != null ? form.getLimit() : DEFAULT_LIMIT;
        form.setTopK(limit);
        FetchNearestResourcesForm nearestForm = new FetchNearestResourcesForm();
        nearestForm.setId(focus.getId());
        nearestForm.setEndLabels(List.of("TIMESERIES"));
        nearestForm.setLimit(limit);
        nearestForm.setRelationshipTypes(form.getRelationshipTypes());
        nearestForm.setExcludedLabels(List.of("POLICY"));
        ResourceNetwork network = client.resources().fetchNearest(nearestForm);

        // Candidate node ids (everything reachable except the focus); keep the focus's graph resource.
        Set<Long> candidateIds = new HashSet<>();
        Resource focusResource = null;
        for (Resource r : network.nodes()) {
            if (r.getId() == null) {
                continue;
            }
            if (r.getId().equals(focus.getId())) {
                focusResource = r;
            } else {
                candidateIds.add(r.getId());
            }
        }

        // Resolve which candidate ids are actually numeric timeseries. /timeseries/byids is
        // node-type-filtered, so non-timeseries nodes (datasets, etc.) simply don't come back.
        Map<Long, Timeseries> numericCandidates = new HashMap<>();
        if (!candidateIds.isEmpty()) {
            List<IdCollection> ids = new ArrayList<>();
            for (Long id : candidateIds) {
                IdCollection ic = new IdCollection();
                ic.setId(id);
                ids.add(ic);
            }
            for (Timeseries ts : client.timeseries().byIds(ids).getItems()) {
                if (ts.getId() != null && isNumeric(ts.getValueType())) {
                    numericCandidates.put(ts.getId(), ts);
                }
            }
        }

        // Fetch focus + candidate datapoints in one aggregated batch; ACL filtering happens in the api SQL.
        DataRetriever<RetrieveFilter> retriever = new DataRetriever<>();
        retriever.getItems().add(filterFor(focus.getId(), form, bucketSeconds));
        for (Long id : numericCandidates.keySet()) {
            retriever.getItems().add(filterFor(id, form, bucketSeconds));
        }
        DataWrapper<DataCollection<DatapointAggsDTO>> data = client.timeseries().retrieveAggregated(retriever);

        Map<Long, DataCollection<DatapointAggsDTO>> byId = new HashMap<>();
        for (DataCollection<DatapointAggsDTO> dc : data.getItems()) {
            if (dc.getId() != null) {
                byId.put(dc.getId(), dc);
            }
        }

        // Focus data is required to analyse, but an empty window is a normal outcome — not an error.
        // Return 200 with an explanatory message and no results, so the UI shows a note.
        DataCollection<DatapointAggsDTO> focusData = byId.get(focus.getId());
        if (focusData == null || focusData.getDatapoints() == null || focusData.getDatapoints().isEmpty()) {
            AnalysisResponse empty = new AnalysisResponse();
            empty.setBucketSeconds(bucketSeconds);
            if (focusResource != null) {
                empty.setFocus(focusResource);
            }
            empty.setMessage("No data for this time series in the selected range.");
            return empty;
        }

        // Candidate collections — include every numeric candidate; those with no readable data get an
        // empty collection so the compute reports them as skipped.
        List<DataCollection<DatapointAggsDTO>> candidateData = new ArrayList<>();
        for (Map.Entry<Long, Timeseries> entry : numericCandidates.entrySet()) {
            DataCollection<DatapointAggsDTO> dc = byId.get(entry.getKey());
            if (dc == null) {
                dc = new DataCollection<>();
                dc.setId(entry.getKey());
                dc.setExternalId(entry.getValue().getExternalId());
            }
            candidateData.add(dc);
        }

        form.setFocusId(focus.getId());

        AnalysisComputeRequest req = new AnalysisComputeRequest();
        req.setForm(form);
        req.setBucketSeconds(bucketSeconds);
        req.setFocus(focusData);
        req.setCandidates(candidateData);
        req.setEdges(new ArrayList<>(network.edges()));

        AnalysisResponse resp = computer.compute(req);

        // The compute works on ids/externalIds only; fill display names from the resolved resources.
        if (focusResource != null) {
            resp.setFocus(focusResource);
        } else if (resp.getFocus() != null) {
            resp.getFocus().setName(focus.getName());
        }
        for (AnalysisResult r : resp.getResults()) {
            Timeseries ts = r.getId() != null ? numericCandidates.get(r.getId()) : null;
            if (ts != null) {
                r.setName(ts.getName());
            }
        }
        return resp;
    }

    private Timeseries resolveFocus(DatahubClient client, AnalysisForm form) {
        IdCollection ic = new IdCollection();
        ic.setId(form.getFocusId());
        ic.setExternalId(form.getFocusExternalId());
        List<Timeseries> found = new ArrayList<>(client.timeseries().byIds(List.of(ic)).getItems());
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Derive the bucket width (seconds) from the window span alone (~{@link #TARGET_BUCKETS} buckets),
     * so it can never be mismatched. The compute's fixed lag reach then gives a max lead/lag delay in
     * time that scales with the window.
     */
    private static int deriveBucketSeconds(long startSec, long endSec) {
        long span = Math.max(1, endSec - startSec);
        return (int) Math.max(1, Math.round((double) span / TARGET_BUCKETS));
    }

    private RetrieveFilter filterFor(Long id, AnalysisForm form, int bucketSeconds) {
        RetrieveFilter f = new RetrieveFilter();
        f.setId(id);
        f.setStart(form.getStart());
        f.setEnd(form.getEnd());
        f.setAggregates(List.of("avg"));
        f.setGranularity(bucketSeconds + " second"); // ClickHouse INTERVAL <n> SECOND
        f.setLimit(FETCH_LIMIT);
        return f;
    }

    /**
     * The api models a series' value type as a lowercase string (float32/bigint/numeric/decimal32/…);
     * only {@code text} and {@code mixed} are non-numeric.
     */
    private static boolean isNumeric(String valueType) {
        if (valueType == null) {
            return false;
        }
        return !valueType.equalsIgnoreCase("text") && !valueType.equalsIgnoreCase("mixed");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
