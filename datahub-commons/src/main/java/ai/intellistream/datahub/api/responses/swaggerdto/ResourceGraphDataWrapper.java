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
}
