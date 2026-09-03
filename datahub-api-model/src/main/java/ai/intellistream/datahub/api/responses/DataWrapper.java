// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import ai.intellistream.datahub.models.policy.PolicyWarning;
import ai.intellistream.datahub.models.validation.FieldLimits;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRootName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@JsonRootName(value = "data")
@Schema(name="DataWrapper", description="DataWrapper with items")
public class DataWrapper<T> {

    // Bounded on the request side: every create/update/delete endpoint that is not GraphDataWrapper
    // binds this envelope, and an unbounded items[] is how a caller turns many small entities into
    // bulk storage. Responses are never bean-validated, so paging is unaffected.
    @JacksonXmlElementWrapper(useWrapping = false)
    @Valid
    @Size(max = FieldLimits.BATCH_ITEMS_MAX)
    private Collection<T> items = new ArrayList<>();

    /**
     * Policy warnings raised while writing these items, or null when there were none.
     *
     * <p>Null rather than an empty list, and {@code NON_EMPTY} rather than {@code ALWAYS}, so the
     * field is absent from any response that has nothing to report. Existing clients therefore see
     * no change at all — which is the only way to add a field to an established envelope without
     * breaking a parser somewhere.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Policy violations that were allowed through and recorded for review. "
            + "Absent when there are none.")
    private Collection<PolicyWarning> warnings;

    /**
     * Where this page stopped, to be sent back as {@code cursor} for the next one. Absent when
     * there is no next page — so "keep paging while nextCursor is present" is the whole loop.
     *
     * <p>{@code NON_EMPTY} like {@code warnings} above: a response that cannot be paged carries no
     * field at all, so no existing client sees a change. This had to be added before paging was
     * usable — the API accepted a {@code cursor} and never handed one out, which left callers
     * reverse-engineering the encoding from the last row of the previous page.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Opaque cursor for the next page. Send it back as `cursor`. "
            + "Absent when there are no further pages.")
    private String nextCursor;

    public String getNextCursor() {
        return nextCursor;
    }

    public DataWrapper<T> setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
        return this;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<T> getItems() {
        return items;
    }

    public DataWrapper<T> setItems(Collection<T> items) {
        this.items = items;
        return this;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Collection<PolicyWarning> getWarnings() {
        return warnings;
    }

    public DataWrapper<T> setWarnings(Collection<PolicyWarning> warnings) {
        this.warnings = (warnings == null || warnings.isEmpty()) ? null : warnings;
        return this;
    }

}
