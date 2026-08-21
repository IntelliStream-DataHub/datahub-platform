// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.forms;

import ai.intellistream.datahub.validation.resources.AtLeastOneNotNull;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Request for the timeseries relationship-analysis endpoint ({@code POST /analysis} on datahub-analysis).
 *
 * <p>Identify a focus timeseries by {@code focusId} or {@code focusExternalId}; the backend traverses
 * the graph from it to discover candidates and characterises each candidate against the focus over
 * {@code [start, end]} resampled to a bucket width derived from the window span.
 */
@Data
@AtLeastOneNotNull(fieldNames = {"focusId", "focusExternalId"})
public class AnalysisForm {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long focusId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String focusExternalId;

    private ZonedDateTime start;

    private ZonedDateTime end;

    /**
     * Max nearest-neighbour timeseries to analyse and return, ordered by BFS hop-distance from the
     * focus — the analysis explores outward and keeps the closest {@code limit}. The UI's "expand
     * search" raises this in fixed steps to reach further.
     */
    @Min(1)
    @Max(200)
    private Integer limit = 10;

    /** Relationship types the traversal may follow; null/empty = all. */
    private List<String> relationshipTypes;

    // Note: there is deliberately no granularity knob — the bucket width is derived from the
    // window so it can't be mismatched to it (see AnalysisService.deriveBucketSeconds). Likewise the
    // cross-correlation lag reach is not a request input: the compute uses a fixed bin count
    // (AnalysisComputer.MAX_LAG_BINS), so the max delay in time = that × bucketSeconds scales with
    // the window.

    /** ARIMA AR order used to pre-whiten each series. */
    @Min(0)
    @Max(10)
    private Integer arimaP = 1;

    /** ARIMA differencing order. */
    @Min(0)
    @Max(2)
    private Integer arimaD = 1;

    /** ARIMA MA order (only 0 is currently implemented). */
    @Min(0)
    @Max(0)
    private Integer arimaQ = 0;

    /** Welch coherence segment length (buckets); null = auto power-of-two near overlap/8. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer coherenceSegmentLength;

    /** Number of subwindows for the correlation-stability flag. */
    @Min(2)
    @Max(50)
    private Integer stabilityWindows = 6;

    /** Maximum ranked candidates to return. */
    @Min(1)
    @Max(200)
    private Integer topK = 25;

    /**
     * Minimum fraction of the aligned span that must be REAL (non-missing) data to analyse a candidate;
     * the remainder is linearly interpolated to give the math contiguous arrays. At 0.8 a candidate
     * needs ≥80% coverage, capping synthetic interpolation at ~a fifth of the span so a large gap can't
     * fabricate structure (e.g. a straight-line segment reading as spurious correlation/coherence).
     */
    private Double minOverlap = 0.8;

    /**
     * Which analyses to run; null/empty = all. Values: {@code raw}, {@code whitened},
     * {@code cointegration}, {@code coherence}, {@code stability}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Set<String> analyses;
}
