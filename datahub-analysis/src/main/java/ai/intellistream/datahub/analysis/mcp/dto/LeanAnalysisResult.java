// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp.dto;

import ai.intellistream.datahub.models.analysis.AnalysisResult;
import ai.intellistream.datahub.models.analysis.PathHop;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One candidate of an {@code analysis_related_series} result, trimmed for an LLM context window.
 *
 * <p>What {@link AnalysisResult} carries beyond this is deliberately dropped, not elided:
 * <ul>
 *   <li>{@code coherenceSpectrum} — a full Welch spectrum per candidate, thousands of floats at the
 *       default limit. {@link #peakCoherence}/{@link #peakCoherencePeriodSeconds} keep the verdict.</li>
 *   <li>{@code *LagBuckets} — redundant with the lag in seconds once bucket width is known.</li>
 *   <li>test statistics ({@code haughBoxStat}, {@code cointegrationStat}, {@code cointegrationCrit5pct},
 *       {@code stabilitySignConsistency}, {@code stabilityCorrStdDev}) — the boolean verdicts and the
 *       p-value are the interpretable part; the console's Analyze tab shows the full numbers.</li>
 *   <li>{@code path} as hop objects — flattened to one {@code "a -FEEDS-> b -MEASURES-> c"} string.</li>
 * </ul>
 * Analyses that were not run leave their fields null; {@code AnalysisMcpResultConverter} strips them.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanAnalysisResult(
        String externalId,
        String name,
        Integer overlapCount,
        String path,
        Double rawCorrelation,
        Long rawLagSeconds,
        Double whitenedCorrelation,
        Long whitenedLagSeconds,
        Double haughBoxPValue,
        Boolean whitenedSignificant,
        Boolean cointegrated,
        Boolean stable,
        Double peakCoherence,
        Double peakCoherencePeriodSeconds,
        Double rankScore
) {
    public static LeanAnalysisResult from(AnalysisResult r, Map<Long, String> namesById) {
        return new LeanAnalysisResult(
                r.getExternalId(),
                r.getName(),
                r.getOverlapCount(),
                flattenPath(r.getPath(), namesById),
                r.getRawCorrelation(),
                r.getRawLagSeconds(),
                r.getWhitenedCorrelation(),
                r.getWhitenedLagSeconds(),
                r.getHaughBoxPValue(),
                r.getWhitenedSignificant(),
                r.getCointegrated(),
                r.getStable(),
                r.getPeakCoherence(),
                r.getPeakCoherencePeriodSeconds(),
                r.getRankScore());
    }

    /**
     * {@code [(1→2 FEEDS), (2→3 MEASURES)]} becomes {@code "pump_a -FEEDS-> flow_b -MEASURES-> temp_c"},
     * naming nodes by externalId where the response resolved one and {@code #<id>} otherwise
     * (intermediate hop nodes are not part of the ranked results, so not all ids have names in hand).
     */
    private static String flattenPath(List<PathHop> path, Map<Long, String> namesById) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        StringBuilder s = new StringBuilder(nodeName(path.get(0).getFromId(), namesById));
        for (PathHop hop : path) {
            s.append(" -").append(hop.getRelationshipType()).append("-> ")
                    .append(nodeName(hop.getToId(), namesById));
        }
        return s.toString();
    }

    private static String nodeName(Long id, Map<Long, String> namesById) {
        String name = id == null ? null : namesById.get(id);
        return name != null ? name : "#" + id;
    }
}
