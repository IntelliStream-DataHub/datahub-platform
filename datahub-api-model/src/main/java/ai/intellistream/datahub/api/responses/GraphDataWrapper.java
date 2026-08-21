// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import ai.intellistream.datahub.models.policy.PolicyWarning;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "Graph Data Nodes and Edges", description = "Graph Data Nodes and Edges Object")
public class GraphDataWrapper<T, R> {

    @Valid
    @Size(max = 1000)
    private Collection<T> nodes = new ArrayList<>();

    @Valid
    @Size(max = 1000)
    private Collection<R> relations = new ArrayList<>();

    /**
     * Policy warnings raised while writing these nodes, or null when there were none. Absent from
     * the response when empty, so existing clients see no change. See {@code DataWrapper.warnings}.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Policy violations that were allowed through and recorded for review. "
            + "Absent when there are none.")
    private Collection<PolicyWarning> warnings;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<T> getNodes() {
        return nodes;
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<R> getRelations() {
        return relations;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Collection<PolicyWarning> getWarnings() {
        return warnings;
    }

    public void setWarnings(Collection<PolicyWarning> warnings) {
        this.warnings = (warnings == null || warnings.isEmpty()) ? null : warnings;
    }
}
