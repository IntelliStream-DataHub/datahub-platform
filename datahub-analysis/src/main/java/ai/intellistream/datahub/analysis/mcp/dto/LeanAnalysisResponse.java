// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp.dto;

import ai.intellistream.datahub.models.analysis.AnalysisResponse;
import ai.intellistream.datahub.models.analysis.AnalysisResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lean view of an {@link AnalysisResponse} for the {@code analysis_related_series} MCP tool:
 * the focus identified by externalId/name, the two numbers needed to interpret lags
 * ({@code bucketSeconds}, {@code maxLagSeconds}), and the ranked {@link LeanAnalysisResult}s.
 * Skipped candidates and the "nothing to analyse" message pass through so the model can say
 * why a series is absent instead of inventing a reason.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanAnalysisResponse(
        String focusExternalId,
        String focusName,
        Integer bucketSeconds,
        Long maxLagSeconds,
        List<LeanAnalysisResult> results,
        List<String> skipped,
        String message
) {
    public static LeanAnalysisResponse from(AnalysisResponse resp) {
        // Path hops name nodes by numeric id; resolve to externalIds via the focus and the
        // ranked candidates (the only nodes the response itself carries).
        Map<Long, String> namesById = new HashMap<>();
        if (resp.getFocus() != null && resp.getFocus().getId() != null) {
            namesById.put(resp.getFocus().getId(), resp.getFocus().getExternalId());
        }
        for (AnalysisResult r : resp.getResults()) {
            if (r.getId() != null && r.getExternalId() != null) {
                namesById.put(r.getId(), r.getExternalId());
            }
        }
        return new LeanAnalysisResponse(
                resp.getFocus() != null ? resp.getFocus().getExternalId() : null,
                resp.getFocus() != null ? resp.getFocus().getName() : null,
                resp.getBucketSeconds(),
                resp.getBand() != null ? resp.getBand().getMaxLagSeconds()
                        : (long) resp.getMaxLag() * resp.getBucketSeconds(),
                resp.getResults().stream().map(r -> LeanAnalysisResult.from(r, namesById)).toList(),
                resp.getSkipped(),
                resp.getMessage());
    }
}
