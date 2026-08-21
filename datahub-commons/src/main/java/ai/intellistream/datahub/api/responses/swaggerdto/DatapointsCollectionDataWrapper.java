// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.api.responses.DatapointsCollection;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(description = "Add either id or external id or a mix of both to the request body and the datapoints you want to insert")
public class DatapointsCollectionDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<DatapointsCollection> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<DatapointsCollection> getItems() {
        return items;
    }

}
