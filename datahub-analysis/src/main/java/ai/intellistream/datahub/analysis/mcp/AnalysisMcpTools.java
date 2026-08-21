// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp;

import ai.intellistream.datahub.analysis.compute.AnalysisService;
import ai.intellistream.datahub.analysis.mcp.dto.LeanAnalysisResponse;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The analysis engine's MCP tool surface. Parameters are flat scalars (not the nested
 * {@link AnalysisForm}) so the generated JSON schema stays simple for the model; the tool
 * builds the form and delegates to the same {@link AnalysisService} the REST endpoint uses,
 * so the numeric engine and the JWT-forwarding data gathering are untouched. Read-only.
 */
@Component
public class AnalysisMcpTools {

    /** Values {@link AnalysisForm#getAnalyses()} accepts; anything else is a caller typo worth failing on. */
    private static final Set<String> KNOWN_ANALYSES =
            Set.of("raw", "whitened", "cointegration", "coherence", "stability");

    private final AnalysisService analysisService;

    public AnalysisMcpTools(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Tool(
            resultConverter = AnalysisMcpResultConverter.class,
            name = "analysis_related_series",
            description = """
                    Find which time series are statistically related to a focus series over a time
                    window, and how. Walks the knowledge graph outward from the focus for physically
                    related candidate series, then tests each pair: cross-correlation with lead/lag
                    (raw and ARIMA-prewhitened with Haugh-Box significance), Engle-Granger
                    cointegration, correlation stability across subwindows, and Welch coherence
                    (shared periodicity). Returns candidates ranked by evidence strength.

                    Use this instead of fetching raw datapoints whenever the question is about
                    cause, influence, correlation, or which signals move together. A positive lag
                    means the candidate lags the focus (the focus leads); whitenedSignificant=true
                    means the correlation survives prewhitening and is unlikely to be spurious.
                    """
    )
    public LeanAnalysisResponse relatedSeries(
            @ToolParam(description = "ExternalId of the focus timeseries.")
            String focusExternalId,
            @ToolParam(description = "Start of the window (inclusive), ISO-8601 (e.g. '2026-08-01T00:00:00Z').")
            String start,
            @ToolParam(description = "End of the window (exclusive), ISO-8601.")
            String end,
            @ToolParam(required = false, description =
                    "Max candidate series to analyse, nearest-first by graph distance (default 10, max 200).")
            Integer limit,
            @ToolParam(required = false, description =
                    "Comma-separated relationship types the graph traversal may follow (default: all).")
            String relationshipTypes,
            @ToolParam(required = false, description =
                    "Comma-separated analyses to run: raw, whitened, cointegration, coherence, stability (default: all).")
            String analyses
    ) {
        AnalysisForm form = new AnalysisForm();
        form.setFocusExternalId(focusExternalId);
        form.setStart(ZonedDateTime.parse(start));
        form.setEnd(ZonedDateTime.parse(end));
        if (limit != null) {
            form.setLimit(limit);
        }
        List<String> types = splitCsv(relationshipTypes);
        if (!types.isEmpty()) {
            form.setRelationshipTypes(types);
        }
        List<String> requested = splitCsv(analyses);
        if (!requested.isEmpty()) {
            Set<String> selected = new LinkedHashSet<>();
            for (String a : requested) {
                String normalized = a.toLowerCase();
                if (!KNOWN_ANALYSES.contains(normalized)) {
                    throw new IllegalArgumentException(
                            "Unknown analysis '" + a + "'; allowed: " + KNOWN_ANALYSES);
                }
                selected.add(normalized);
            }
            form.setAnalyses(selected);
        }
        return LeanAnalysisResponse.from(analysisService.analyze(form));
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
