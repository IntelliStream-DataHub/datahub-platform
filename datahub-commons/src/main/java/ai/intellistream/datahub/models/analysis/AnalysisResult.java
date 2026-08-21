// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * The relationship between the focus timeseries and one candidate, characterised from several angles.
 * Fields for an analysis that was not run (or could not be computed for this candidate) are left null.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResult {

    private Long id;
    private String externalId;
    private String name;

    /** Number of aligned, non-missing buckets used. */
    private Integer overlapCount;

    /** Graph relationship path focus → candidate. */
    private List<PathHop> path;

    // --- Raw cross-correlation (levels) ---
    private Double rawCorrelation;
    private Integer rawLagBuckets;
    private Long rawLagSeconds;

    // --- Haugh–Box whitened cross-correlation ---
    private Double whitenedCorrelation;
    private Integer whitenedLagBuckets;
    private Long whitenedLagSeconds;
    private Double haughBoxStat;
    private Double haughBoxPValue;
    private Boolean whitenedSignificant;

    // --- Engle–Granger cointegration ---
    private Boolean cointegrated;
    private Double cointegrationStat;
    private Double cointegrationCrit5pct;

    // --- Subwindow correlation stability ---
    private Double stabilitySignConsistency;
    private Double stabilityCorrStdDev;
    private Boolean stable;

    // --- Welch coherence ---
    private Double peakCoherence;
    private Double peakCoherencePeriodSeconds;
    private List<CoherencePoint> coherenceSpectrum;

    /** Ranking key: max(|whitened|, |raw|) correlation. */
    private Double rankScore;
}
