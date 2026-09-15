// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import ai.intellistream.datahub.models.policy.PolicyWarning;
import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "GraphResources", description = "Resources and Relations for graph data network.")
public class ResourceGraphDataWrapper {

    @Size(max = 1000)
    @ArraySchema(schema = @Schema(anyOf = {
            Asset.class, Resource.class, Timeseries.class,
            DataSetModel.class, Policy.class, Function.class}))
    private Collection<NodeModel> nodes = new ArrayList<>();

    @Size(max = 1000)
    private Collection<EdgeProxy> relations = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<NodeModel> getNodes() {
        return nodes;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<EdgeProxy> getRelations() {
        return relations;
    }

    /**
     * Mirrors {@code DataWrapper.warnings}. Attached on the way out by
     * {@code PolicyWarningResponseAdvice} for the write paths that evaluate policies
     * ({@code ResourceService}, {@code TimeseriesService}, {@code DataSetService},
     * {@code GraphTransferService}), and absent when there is nothing to report.
     */
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Policy violations that were allowed through and recorded for review. "
                    + "Absent when there are none.")
    private Collection<PolicyWarning> warnings;

    public Collection<PolicyWarning> getWarnings() {
        return warnings;
    }
}
