// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "CreateResources",
        description = "Create request for the graph data network. Each node body is dispatched by "
                + "its type-label: ASSET builds an asset (may carry geoLocation), TIMESERIES a "
                + "time series (may carry unit/valueType), DATASET a data set, POLICY a policy, "
                + "FUNCTION a function; no type-label builds a plain resource. DATASET and POLICY "
                + "creates require the all-datasets manage grant.")
public class CreateResources {

    @Size(max = 1000)
    @ArraySchema(schema = @Schema(anyOf = {
            Asset.class, Resource.class, Timeseries.class,
            DataSetModel.class, Policy.class, Function.class}))
    private Collection<NodeModel> nodes = new ArrayList<>();

    @Size(max = 1000)
    private Collection<RelForm> relations = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<NodeModel> getNodes() {
        return nodes;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<RelForm> getRelations() {
        return relations;
    }

}