// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * The timescales the analysis actually probed, auto-derived from granularity and window length so the
 * UI can show exactly what was measured (e.g. "coherence 2 min – 2 h, lead/lag ±50 min").
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TimescaleBand {

    /** Fastest resolvable period = 2 × bucketSeconds. */
    private long nyquistPeriodSeconds;

    /** Lead/lag scan reach = maxLag × bucketSeconds. */
    private long maxLagSeconds;

    /** Shortest coherence period (null if coherence not computed). */
    private Long coherenceMinPeriodSeconds;

    /** Longest coherence period ≈ window/8 (null if coherence not computed). */
    private Long coherenceMaxPeriodSeconds;

    /** Number of averaged Welch segments (null if coherence not computed). */
    private Integer coherenceSegments;
}
