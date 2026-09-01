// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Node Collection",
        description = "Data response with a node collection. Items are typed by their type-label: "
                + "an element whose labels contain ASSET is an Asset, TIMESERIES a Timeseries, "
                + "DATASET a data set, POLICY a policy, FUNCTION a function; an element with no "
                + "type-label is a plain Resource.")
public class ResourceDataWrapper{

    @JacksonXmlElementWrapper(useWrapping = false)
    @ArraySchema(schema = @Schema(anyOf = {
            Asset.class, Resource.class, Timeseries.class,
            DataSetModel.class, Policy.class, Function.class}))
    private Collection<NodeModel> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<NodeModel> getItems() {
        return items;
    }
}
