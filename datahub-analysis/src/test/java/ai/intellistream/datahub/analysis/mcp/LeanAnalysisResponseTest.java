// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp;

import ai.intellistream.datahub.analysis.mcp.dto.LeanAnalysisResponse;
import ai.intellistream.datahub.analysis.mcp.dto.LeanAnalysisResult;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.analysis.AnalysisResponse;
import ai.intellistream.datahub.models.analysis.AnalysisResult;
import ai.intellistream.datahub.models.analysis.CoherencePoint;
import ai.intellistream.datahub.models.analysis.PathHop;
import ai.intellistream.datahub.models.analysis.TimescaleBand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shape of what {@code analysis_related_series} feeds the model: the per-candidate
 * coherence spectrum must never appear, paths flatten to one string, and a full default-limit
 * response stays within a fixed character budget. The budget test is the guard that keeps a
 * future "just add the field" edit from silently multiplying the tool's context cost.
 */
class LeanAnalysisResponseTest {

    /** Serialized size cap for a fully-populated 10-candidate response. */
    private static final int CHAR_BUDGET = 6000;

    private final AnalysisMcpResultConverter converter = new AnalysisMcpResultConverter();

    @Test
    void dropsSpectrumFlattensPathAndKeepsVerdicts() {
        AnalysisResponse resp = response(1);
        AnalysisResult full = resp.getResults().get(0);
        full.setPath(List.of(
                new PathHop(100L, 55L, "FEEDS"),
                new PathHop(55L, 201L, "MEASURED_BY")));

        LeanAnalysisResponse lean = LeanAnalysisResponse.from(resp);
        LeanAnalysisResult r = lean.results().get(0);

        // Path names resolve through focus + candidates; the intermediate node falls back to #id.
        assertThat(r.path()).isEqualTo("pump_flow -FEEDS-> #55 -MEASURED_BY-> candidate_1");
        assertThat(r.externalId()).isEqualTo("candidate_1");
        assertThat(r.rawLagSeconds()).isEqualTo(120L);
        assertThat(r.whitenedSignificant()).isTrue();
        assertThat(r.cointegrated()).isTrue();
        assertThat(r.rankScore()).isEqualTo(0.87);
        assertThat(lean.focusExternalId()).isEqualTo("pump_flow");
        assertThat(lean.maxLagSeconds()).isEqualTo(3000L);

        String json = converter.convert(lean, LeanAnalysisResponse.class);
        assertThat(json)
                .doesNotContain("coherenceSpectrum")
                .doesNotContain("LagBuckets")
                .doesNotContain("haughBoxStat")
                .contains("peakCoherence");
    }

    @Test
    void fullDefaultLimitResponseStaysWithinBudget() {
        String json = converter.convert(LeanAnalysisResponse.from(response(10)), LeanAnalysisResponse.class);
        assertThat(json.length())
                .as("10 fully-populated candidates must serialize under %s chars, was %s",
                        CHAR_BUDGET, json.length())
                .isLessThan(CHAR_BUDGET);
    }

    @Test
    void emptyResultCarriesTheMessageThrough() {
        AnalysisResponse resp = new AnalysisResponse();
        resp.setBucketSeconds(60);
        resp.setMessage("No data for this time series in the selected range.");

        LeanAnalysisResponse lean = LeanAnalysisResponse.from(resp);

        assertThat(lean.results()).isEmpty();
        assertThat(lean.message()).contains("No data");
        // NON_EMPTY keeps the empty results list out of what the model sees.
        assertThat(converter.convert(lean, LeanAnalysisResponse.class)).doesNotContain("results");
    }

    /** A response shaped like a real run: every analysis populated, including the heavy spectrum. */
    private static AnalysisResponse response(int candidates) {
        AnalysisResponse resp = new AnalysisResponse();
        Resource focus = new Resource();
        focus.setId(100L);
        focus.setExternalId("pump_flow");
        focus.setName("Pump discharge flow");
        resp.setFocus(focus);
        resp.setGranularity("1 minute");
        resp.setBucketSeconds(60);
        resp.setMaxLag(50);
        TimescaleBand band = new TimescaleBand();
        band.setNyquistPeriodSeconds(120);
        band.setMaxLagSeconds(3000);
        band.setCoherenceMinPeriodSeconds(120L);
        band.setCoherenceMaxPeriodSeconds(3600L);
        band.setCoherenceSegments(8);
        resp.setBand(band);
        for (int i = 1; i <= candidates; i++) {
            resp.getResults().add(result(i));
        }
        resp.getSkipped().add("sparse_series_a");
        return resp;
    }

    private static AnalysisResult result(int i) {
        AnalysisResult r = new AnalysisResult();
        r.setId(200L + i);
        r.setExternalId("candidate_" + i);
        r.setName("Candidate series " + i);
        r.setOverlapCount(480);
        r.setPath(List.of(new PathHop(100L, 200L + i, "FEEDS")));
        r.setRawCorrelation(0.91);
        r.setRawLagBuckets(2);
        r.setRawLagSeconds(120L);
        r.setWhitenedCorrelation(0.44);
        r.setWhitenedLagBuckets(2);
        r.setWhitenedLagSeconds(120L);
        r.setHaughBoxStat(31.4);
        r.setHaughBoxPValue(0.003);
        r.setWhitenedSignificant(true);
        r.setCointegrated(true);
        r.setCointegrationStat(-4.1);
        r.setCointegrationCrit5pct(-3.34);
        r.setStabilitySignConsistency(1.0);
        r.setStabilityCorrStdDev(0.05);
        r.setStable(true);
        r.setPeakCoherence(0.82);
        r.setPeakCoherencePeriodSeconds(900.0);
        // The heavy field the lean projection must drop: a realistic spectrum length.
        List<CoherencePoint> spectrum = new ArrayList<>();
        for (int f = 1; f <= 128; f++) {
            spectrum.add(new CoherencePoint(f / 7200.0, 7200.0 / f, 0.5));
        }
        r.setCoherenceSpectrum(spectrum);
        r.setRankScore(0.87);
        return r;
    }
}
