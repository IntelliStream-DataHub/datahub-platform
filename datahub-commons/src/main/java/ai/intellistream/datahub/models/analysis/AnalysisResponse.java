// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.analysis;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Resource;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Response of the relationship-analysis endpoint: ranked candidates plus the probed timescale band. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResponse {

    /** The focus timeseries node, typed by its label (a Timeseries in practice). */
    private NodeModel focus;

    private String granularity;

    private int bucketSeconds;

    private int maxLag;

    /** The auto-derived timescale band the analysis covered. */
    private TimescaleBand band;

    /** Candidates ranked by {@link AnalysisResult#getRankScore()} descending. */
    private List<AnalysisResult> results = new ArrayList<>();

    /** External IDs of candidates that were discovered but could not be analysed (too sparse, etc.). */
    private List<String> skipped = new ArrayList<>();

    /** Set (with empty {@link #results}) when there was nothing to analyse — e.g. no datapoints for the
     * focus in the selected window. A normal 200 outcome the UI shows as a note, not an error. */
    private String message;
}
