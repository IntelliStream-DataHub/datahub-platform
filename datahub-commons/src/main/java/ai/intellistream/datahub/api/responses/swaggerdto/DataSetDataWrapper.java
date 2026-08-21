// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.DataSetModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Data Set Collection", description = "Data Response with DataSet collection.")
public class DataSetDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<DataSetModel> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<DataSetModel> getItems() {
        return items;
    }
}
