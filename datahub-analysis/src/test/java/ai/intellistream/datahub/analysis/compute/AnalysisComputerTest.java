// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.compute;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.jpa.dto.DatapointAggsDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.analysis.AnalysisComputeRequest;
import ai.intellistream.datahub.models.analysis.AnalysisResponse;
import ai.intellistream.datahub.models.analysis.AnalysisResult;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration tests for the pure compute (no Spring, no mocks). Feeds synthetic
 * {@link DataCollection}s and asserts ranking, lead/lag, relationship path, skip handling, and the
 * timescale band — ported from the api's former in-process orchestration test.
 */
class AnalysisComputerTest {

    private static final long FOCUS_ID = 1L;
    private static final long CAND_OK_ID = 2L;
    private static final long CAND_NODATA_ID = 4L;
    private static final int BUCKET = 60;
    private static final int POINTS = 200;
    private static final int LAG = 5;

    private final AnalysisComputer computer = new AnalysisComputer();
    private final ZonedDateTime start = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    void ranksLaggedCandidate_withPath_andSkipsNoData() {
        double[] focusVals = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            focusVals[i] = Math.sin(i * 0.2);
        }
        double[] lagged = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            lagged[i] = Math.sin((i - LAG) * 0.2); // candidate lags focus by LAG buckets
        }

        AnalysisForm form = new AnalysisForm();
        form.setFocusId(FOCUS_ID);
        form.setStart(start);
        form.setEnd(start.plusSeconds((long) POINTS * BUCKET));

        AnalysisComputeRequest req = new AnalysisComputeRequest();
        req.setForm(form);
        req.setBucketSeconds(BUCKET);
        req.setFocus(collection(FOCUS_ID, "focus", focusVals));
        req.setCandidates(List.of(
                collection(CAND_OK_ID, "cand_ok", lagged),
                empty(CAND_NODATA_ID, "cand_nodata")));
        req.setEdges(List.of(edge(FOCUS_ID, CAND_OK_ID, "FEEDS")));

        AnalysisResponse resp = computer.compute(req);

        assertEquals(1, resp.getResults().size());
        AnalysisResult top = resp.getResults().get(0);
        assertEquals("cand_ok", top.getExternalId());
        assertNotNull(top.getRawCorrelation());
        assertTrue(top.getRawCorrelation() > 0.9, "expected strong correlation, got " + top.getRawCorrelation());
        assertEquals(LAG, top.getRawLagBuckets()); // focus leads by LAG
        assertEquals((long) LAG * BUCKET, top.getRawLagSeconds());
        assertNotNull(top.getPath());
        assertEquals(1, top.getPath().size());
        assertEquals("FEEDS", top.getPath().get(0).getRelationshipType());

        assertTrue(resp.getSkipped().contains("cand_nodata"));
        assertFalse(resp.getResults().stream().anyMatch(r -> "cand_nodata".equals(r.getExternalId())));

        assertNotNull(resp.getBand());
        assertEquals(2L * BUCKET, resp.getBand().getNyquistPeriodSeconds());
        // max delay reach = fixed MAX_LAG_BINS (50) × bucketSeconds
        assertEquals(50L * BUCKET, resp.getBand().getMaxLagSeconds());
    }

    @Test
    void constantSeriesProduceNoNonFiniteValues() {
        // Degenerate case: a flatline focus and a flatline candidate — zero variance everywhere. Nothing
        // may throw, and CRUCIALLY no result field may be NaN/Infinity: one non-finite Double serializes
        // as the invalid JSON token `NaN` and would break the entire response's parse.
        AnalysisResponse resp = computeWith(constant(5.0), constant(-2.0));
        assertFalse(resp.getResults().isEmpty(), "a constant candidate has data ⇒ analysed, not skipped");
        for (AnalysisResult r : resp.getResults()) {
            assertAllFinite(r);
        }
    }

    @Test
    void constantFocusWithVaryingCandidateProducesNoNonFiniteValues() {
        for (AnalysisResult r : computeWith(constant(5.0), sine()).getResults()) {
            assertAllFinite(r);
        }
    }

    @Test
    void varyingFocusWithConstantCandidateProducesNoNonFiniteValues() {
        for (AnalysisResult r : computeWith(sine(), constant(-2.0)).getResults()) {
            assertAllFinite(r);
        }
    }

    private static void assertAllFinite(AnalysisResult r) {
        Double[] scalars = {
                r.getRawCorrelation(), r.getWhitenedCorrelation(), r.getHaughBoxStat(), r.getHaughBoxPValue(),
                r.getCointegrationStat(), r.getCointegrationCrit5pct(), r.getStabilitySignConsistency(),
                r.getStabilityCorrStdDev(), r.getPeakCoherence(), r.getPeakCoherencePeriodSeconds(),
                r.getRankScore()
        };
        for (Double d : scalars) {
            assertTrue(d == null || Double.isFinite(d), "non-finite result field: " + d);
        }
    }

    private AnalysisResponse computeWith(double[] focusVals, double[] candVals) {
        AnalysisForm form = new AnalysisForm();
        form.setFocusId(FOCUS_ID);
        form.setStart(start);
        form.setEnd(start.plusSeconds((long) POINTS * BUCKET));
        AnalysisComputeRequest req = new AnalysisComputeRequest();
        req.setForm(form);
        req.setBucketSeconds(BUCKET);
        req.setFocus(collection(FOCUS_ID, "focus", focusVals));
        req.setCandidates(List.of(collection(CAND_OK_ID, "cand", candVals)));
        req.setEdges(List.of(edge(FOCUS_ID, CAND_OK_ID, "FEEDS")));
        return computer.compute(req);
    }

    private static double[] constant(double c) {
        double[] a = new double[POINTS];
        java.util.Arrays.fill(a, c);
        return a;
    }

    private static double[] sine() {
        double[] a = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            a[i] = Math.sin(i * 0.2);
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

    private static DataCollection<DatapointAggsDTO> empty(long id, String externalId) {
        DataCollection<DatapointAggsDTO> dc = new DataCollection<>();
        dc.setId(id);
        dc.setExternalId(externalId);
        return dc;
    }

    private static EdgeProxy edge(long from, long to, String type) {
        return new EdgeProxy(1L, from, to, type, null, new HashMap<>());
    }
}
