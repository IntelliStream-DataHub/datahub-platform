// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A refused binary datapoint request. Documentation only. */
@Schema(name = "DatapointBlockProblem",
        description = """
                Nothing of the request was published. `type` follows the status; `reason` is the \
                finer, stable sub-code within it.""")
public class DatapointBlockProblem extends ApiProblem {

    @Schema(description = """
            What was wrong, as a stable kebab-case token. A malformed frame (`invalid-frame`) says \
            which rule it broke, e.g. `unsorted` or `schema-mismatch`; an oversized one \
            (`request-too-large`) says which cap, `frame-too-large`, `too-many-frames` or \
            `request-too-large`. The other types carry their own slug. Absent on a 413 or 415 \
            answered before the endpoint read the body.""",
            example = "external-id-mismatch")
    private String reason;

    @Schema(description = "The 0-based index of the frame at fault, when one is.", example = "3")
    private Integer frameIndex;

    @Schema(description = "The series the refusal is about, when particular ones are.", example = "[1041, 1042]")
    private List<Long> timeseriesIds;

    public String getReason() { return reason; }
    public Integer getFrameIndex() { return frameIndex; }
    public List<Long> getTimeseriesIds() { return timeseriesIds; }
}
