// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.compute;

import ai.intellistream.datahub.analysis.ArimaModel;
import ai.intellistream.datahub.analysis.Coherence;
import ai.intellistream.datahub.analysis.Cointegration;
import ai.intellistream.datahub.analysis.CrossCorrelation;
import ai.intellistream.datahub.analysis.HaughBox;
import ai.intellistream.datahub.analysis.Stationarity;
import ai.intellistream.datahub.analysis.TimeGrid;
import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.jpa.dto.DatapointAggsDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.analysis.AnalysisComputeRequest;
import ai.intellistream.datahub.models.analysis.AnalysisResponse;
import ai.intellistream.datahub.models.analysis.AnalysisResult;
import ai.intellistream.datahub.models.analysis.CoherencePoint;
import ai.intellistream.datahub.models.analysis.PathHop;
import ai.intellistream.datahub.models.analysis.TimescaleBand;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure analysis compute: takes already-authorized, already-bucketed series (focus + candidate
 * {@link DataCollection}s) plus the graph edges and the original {@link AnalysisForm}, and produces
 * the {@link AnalysisResponse}. No DB, no auth — just numerics on the engine (see the {@code analysis}
 * package). Display names are the api's concern and are filled in there; the compute works on ids
 * and externalIds only.
 */
@Component
@Slf4j
public class AnalysisComputer {

    /**
     * Fixed cross-correlation lag search half-width, in buckets (not a request input). The bucket
     * width is derived from the window span, so the max lead/lag delay in time
     * ({@code MAX_LAG_BINS × bucketSeconds}, surfaced as {@code band.maxLagSeconds}) scales with the
     * selected window — roughly ±span/10 with the api's default of ~500 buckets per window.
     */
    private static final int MAX_LAG_BINS = 50;

    public AnalysisResponse compute(AnalysisComputeRequest req) {
        AnalysisForm form = req.getForm();
        int bucketSeconds = req.getBucketSeconds();
        long startSec = form.getStart().toEpochSecond();
        long endSec = form.getEnd().toEpochSecond();
        int maxLag = MAX_LAG_BINS;
        int minPoints = Math.max(3 * maxLag, 30);

        DataCollection<DatapointAggsDTO> focus = req.getFocus();
        double[] focusGrid = toGrid(focus, startSec, endSec, bucketSeconds);

        AnalysisResponse response = new AnalysisResponse();
        response.setGranularity(bucketSeconds + " second");
        response.setBucketSeconds(bucketSeconds);
        response.setMaxLag(maxLag);
        response.setFocus(minimalResource(focus));

        TimescaleBand band = new TimescaleBand();
        band.setNyquistPeriodSeconds(2L * bucketSeconds);
        band.setMaxLagSeconds((long) maxLag * bucketSeconds);
        response.setBand(band);

        if (focusGrid == null) {
            return response; // focus has no data — nothing to relate to
        }

        Set<String> analyses = form.getAnalyses();
        Long focusId = focus.getId();
        Set<Long> candidateIds = new HashSet<>();
        for (DataCollection<DatapointAggsDTO> c : req.getCandidates()) {
            if (c.getId() != null) {
                candidateIds.add(c.getId());
            }
        }
        Map<Long, List<PathHop>> paths = shortestPaths(focusId, candidateIds, req.getEdges());

        List<AnalysisResult> results = new ArrayList<>();
        for (DataCollection<DatapointAggsDTO> cand : req.getCandidates()) {
            double[] candGrid = toGrid(cand, startSec, endSec, bucketSeconds);
            if (candGrid == null) {
                response.getSkipped().add(cand.getExternalId());
                continue;
            }
            TimeGrid.Aligned aligned = TimeGrid.align(focusGrid, candGrid, minPoints, form.getMinOverlap());
            if (aligned == null) {
                response.getSkipped().add(cand.getExternalId());
                continue;
            }
            AnalysisResult result = analysePair(cand, aligned, form, bucketSeconds, maxLag, analyses, band);
            result.setPath(cand.getId() != null ? paths.get(cand.getId()) : null);
            results.add(result);
        }

        results.sort(Comparator.comparingDouble(
                (AnalysisResult r) -> r.getRankScore() == null ? -1 : r.getRankScore()).reversed());
        if (results.size() > form.getTopK()) {
            results = new ArrayList<>(results.subList(0, form.getTopK()));
        }
        response.setResults(results);
        return response;
    }

    private AnalysisResult analysePair(DataCollection<DatapointAggsDTO> cand, TimeGrid.Aligned aligned,
                                       AnalysisForm form, int bucketSeconds, int maxLag,
                                       Set<String> analyses, TimescaleBand band) {
        double[] x = aligned.a(); // focus
        double[] y = aligned.b(); // candidate
        AnalysisResult result = new AnalysisResult();
        result.setId(cand.getId());
        result.setExternalId(cand.getExternalId());
        result.setOverlapCount(aligned.overlapCount());

        Double rawAbs = null;
        Double whitenedAbs = null;

        // Unit-root pretest, shared by whitening + cointegration, so neither over-differences nor
        // reports a spurious "lasting link" on stationary series. Computed once, only if needed.
        boolean needsStationarity = enabled(analyses, "whitened") || enabled(analyses, "cointegration");
        boolean xStationary = needsStationarity && Stationarity.isStationary(x);
        boolean yStationary = needsStationarity && Stationarity.isStationary(y);

        if (enabled(analyses, "raw")) {
            try {
                CrossCorrelation.CcfResult ccf = CrossCorrelation.ccf(x, y, maxLag);
                result.setRawCorrelation(ccf.bestR());
                result.setRawLagBuckets(ccf.bestLag());
                result.setRawLagSeconds((long) ccf.bestLag() * bucketSeconds);
                rawAbs = Math.abs(ccf.bestR());
            } catch (RuntimeException e) {
                log.debug("raw CCF failed for {}: {}", cand.getExternalId(), e.getMessage());
            }
        }

        if (enabled(analyses, "stability")) {
            try {
                CrossCorrelation.Stability st = CrossCorrelation.stability(x, y, form.getStabilityWindows());
                if (st != null) {
                    result.setStabilitySignConsistency(st.signConsistency());
                    result.setStabilityCorrStdDev(st.corrStdDev());
                    result.setStable(st.stable());
                }
            } catch (RuntimeException e) {
                log.debug("stability failed for {}: {}", cand.getExternalId(), e.getMessage());
            }
        }

        if (enabled(analyses, "whitened")) {
            try {
                int p = form.getArimaP();
                // Difference only as much as the data needs (a shared d keeps the two residual series
                // index-aligned for Haugh–Box): 0 when both are already stationary, up to the form's
                // cap otherwise. Avoids over-differencing stationary series into non-white residuals.
                int d = Math.min(form.getArimaD(), Math.max(xStationary ? 0 : 1, yStationary ? 0 : 1));
                if (x.length >= ArimaModel.minLength(p, d)) {
                    ArimaModel mx = ArimaModel.fit(x, p, d, form.getArimaQ());
                    ArimaModel my = ArimaModel.fit(y, p, d, form.getArimaQ());
                    HaughBox.Result hb = HaughBox.analyze(mx.residuals(), my.residuals(), maxLag);
                    result.setWhitenedCorrelation(hb.bestR());
                    result.setWhitenedLagBuckets(hb.bestLag());
                    result.setWhitenedLagSeconds((long) hb.bestLag() * bucketSeconds);
                    result.setHaughBoxStat(hb.statistic());
                    result.setHaughBoxPValue(hb.pValue());
                    result.setWhitenedSignificant(hb.significant());
                    whitenedAbs = Math.abs(hb.bestR());
                }
            } catch (RuntimeException e) {
                log.debug("Haugh-Box failed for {}: {}", cand.getExternalId(), e.getMessage());
            }
        }

        if (enabled(analyses, "cointegration")) {
            try {
                // Only meaningful when BOTH series are non-stationary (they can share a stochastic
                // trend). On stationary inputs the regression residual is trivially stationary, so the
                // test would always fire — leave the flag unset rather than show a spurious link.
                if (x.length >= Cointegration.minLength() && !xStationary && !yStationary) {
                    Cointegration.Result co = Cointegration.test(x, y);
                    result.setCointegrated(co.cointegrated());
                    result.setCointegrationStat(co.adfStat());
                    result.setCointegrationCrit5pct(co.crit5());
                }
            } catch (RuntimeException e) {
                log.debug("cointegration failed for {}: {}", cand.getExternalId(), e.getMessage());
            }
        }

        if (enabled(analyses, "coherence")) {
            try {
                Coherence.Result coh = Coherence.welch(x, y, bucketSeconds, form.getCoherenceSegmentLength());
                if (coh != null) {
                    result.setPeakCoherence(coh.peakCoherence());
                    result.setPeakCoherencePeriodSeconds(coh.peakPeriodSeconds());
                    List<CoherencePoint> spectrum = new ArrayList<>(coh.coherence().length);
                    for (int i = 0; i < coh.coherence().length; i++) {
                        spectrum.add(new CoherencePoint(coh.frequency()[i], coh.periodSeconds()[i], coh.coherence()[i]));
                    }
                    result.setCoherenceSpectrum(spectrum);
                    if (band.getCoherenceSegments() == null) {
                        band.setCoherenceMinPeriodSeconds((long) coh.minPeriodSeconds());
                        band.setCoherenceMaxPeriodSeconds((long) coh.maxPeriodSeconds());
                        band.setCoherenceSegments(coh.segments());
                    }
                }
            } catch (RuntimeException e) {
                log.debug("coherence failed for {}: {}", cand.getExternalId(), e.getMessage());
            }
        }

        result.setRankScore(compositeRank(rawAbs, whitenedAbs, result.getHaughBoxPValue(),
                result.getCointegrated(), result.getPeakCoherence(), result.getStable()));
        sanitizeNonFinite(result);
        return result;
    }

    /**
     * Null any non-finite Double on the result so a degenerate statistic (NaN/Infinity) can never reach
     * the JSON response. A single non-finite value would otherwise serialize as the invalid token
     * {@code NaN} and break the whole payload's parse — turning one flatline series into a total
     * failure. The per-statistic guards make this rare; this is the belt-and-suspenders net.
     */
    private static void sanitizeNonFinite(AnalysisResult r) {
        r.setRawCorrelation(finite(r.getRawCorrelation()));
        r.setWhitenedCorrelation(finite(r.getWhitenedCorrelation()));
        r.setHaughBoxStat(finite(r.getHaughBoxStat()));
        r.setHaughBoxPValue(finite(r.getHaughBoxPValue()));
        r.setCointegrationStat(finite(r.getCointegrationStat()));
        r.setCointegrationCrit5pct(finite(r.getCointegrationCrit5pct()));
        r.setStabilitySignConsistency(finite(r.getStabilitySignConsistency()));
        r.setStabilityCorrStdDev(finite(r.getStabilityCorrStdDev()));
        r.setPeakCoherence(finite(r.getPeakCoherence()));
        r.setPeakCoherencePeriodSeconds(finite(r.getPeakCoherencePeriodSeconds()));
        r.setRankScore(finite(r.getRankScore()));
    }

    /** @return {@code v} if finite, else {@code null} (drops NaN/Infinity before serialization). */
    private static Double finite(Double v) {
        return (v != null && Double.isFinite(v)) ? v : null;
    }

    /**
     * Evidence-weighted "worth investigating" score in [0, 1] used to rank (and top-K) candidates.
     *
     * <p>{@code core} is the coupling strength we actually trust: the whitened cross-correlation, but
     * only when its Haugh–Box portmanteau p-value clears 5% (a calibrated test across all lags, not the
     * lenient per-lag ±2/√n band). An insignificant OR missing whitened test contributes no genuine
     * evidence, so it falls back to the raw correlation at half credit ({@code 0.5·|raw|}) — meaning a
     * pair whitening <em>rejected</em> and a pair we simply couldn't whiten land on the identical base,
     * and a high-but-spurious raw correlation is never rewarded for a whitened number that isn't real.
     *
     * <p>{@code core} is then scaled by stability (unstable ⇒ ×0.5) and lifted by a capped corroboration
     * boost from cointegration and coherence — scaled by the coupling already present, so a lone signal
     * can't top the list, while cointegration can still rescue a genuine lasting link that lives in the
     * levels rather than the differenced innovations the whitened test sees.
     */
    static double compositeRank(Double rawAbs, Double whitenedAbs, Double haughBoxPValue,
                                Boolean cointegrated, Double peakCoherence, Boolean stable) {
        double whitenedGenuine = (whitenedAbs != null && haughBoxPValue != null && haughBoxPValue < 0.05)
                ? whitenedAbs : 0.0;
        double rawWeak = (rawAbs != null) ? 0.5 * rawAbs : 0.0;
        double core = Math.max(whitenedGenuine, rawWeak);
        if (core == 0.0) {
            return 0.0;
        }

        double stability = Boolean.TRUE.equals(stable) ? 1.0
                : Boolean.FALSE.equals(stable) ? 0.5   // unstable ⇒ likely episodic/spurious
                : 0.85;                                // untested ⇒ slight discount
        double evidence = core * stability;            // in [0, 1]

        double corroboration = 0.0;
        if (Boolean.TRUE.equals(cointegrated)) {
            corroboration += 0.5;
        }
        if (peakCoherence != null) {
            corroboration += peakCoherence;
        }
        double boost = 0.2 * Math.min(1.0, corroboration) * Math.min(1.0, 2.0 * core);

        return Math.min(1.0, evidence + boost);
    }

    /** Grid a collection's average buckets onto the shared grid; null if it carried no points. */
    private static double[] toGrid(DataCollection<DatapointAggsDTO> dc, long startSec, long endSec, int bucketSeconds) {
        List<DatapointAggsDTO> dps = dc == null ? null : dc.getDatapoints();
        if (dps == null || dps.isEmpty()) {
            return null;
        }
        long[] ts = new long[dps.size()];
        double[] vals = new double[dps.size()];
        for (int i = 0; i < dps.size(); i++) {
            DatapointAggsDTO dp = dps.get(i);
            ts[i] = dp.getTimestamp() != null ? dp.getTimestamp().toEpochSecond() : startSec - 1;
            vals[i] = dp.getAverage() != null ? dp.getAverage() : Double.NaN;
        }
        return TimeGrid.toGrid(ts, vals, startSec, endSec, bucketSeconds);
    }

    /** BFS (undirected) over the edges to find a shortest relationship path to each target. */
    private static Map<Long, List<PathHop>> shortestPaths(Long focusId, Set<Long> targets, List<EdgeProxy> edges) {
        Map<Long, List<EdgeProxy>> adjacency = new HashMap<>();
        for (EdgeProxy e : edges) {
            adjacency.computeIfAbsent(e.start(), k -> new ArrayList<>()).add(e);
            adjacency.computeIfAbsent(e.end(), k -> new ArrayList<>()).add(e);
        }
        Map<Long, List<PathHop>> result = new HashMap<>();
        Map<Long, EdgeProxy> cameVia = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        if (focusId == null) {
            return result;
        }
        queue.add(focusId);
        visited.add(focusId);
        Set<Long> remaining = new HashSet<>(targets);
        while (!queue.isEmpty() && !remaining.isEmpty()) {
            Long node = queue.poll();
            for (EdgeProxy e : adjacency.getOrDefault(node, List.of())) {
                long next = e.start() == node ? e.end() : e.start();
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next);
                cameVia.put(next, e);
                cameFrom.put(next, node);
                if (remaining.remove(next)) {
                    result.put(next, reconstruct(focusId, next, cameFrom, cameVia));
                }
                queue.add(next);
            }
        }
        return result;
    }

    private static List<PathHop> reconstruct(Long focusId, Long target,
                                             Map<Long, Long> cameFrom, Map<Long, EdgeProxy> cameVia) {
        List<PathHop> hops = new ArrayList<>();
        Long node = target;
        while (node != null && !node.equals(focusId)) {
            Long prev = cameFrom.get(node);
            EdgeProxy e = cameVia.get(node);
            if (prev == null || e == null) {
                break;
            }
            hops.add(0, new PathHop(prev, node, e.type()));
            node = prev;
        }
        return hops;
    }

    private static boolean enabled(Set<String> analyses, String name) {
        return analyses == null || analyses.isEmpty() || analyses.contains(name);
    }

    private static Resource minimalResource(DataCollection<DatapointAggsDTO> focus) {
        Resource r = new Resource();
        r.setId(focus.getId());
        r.setExternalId(focus.getExternalId());
        return r;
    }
}
