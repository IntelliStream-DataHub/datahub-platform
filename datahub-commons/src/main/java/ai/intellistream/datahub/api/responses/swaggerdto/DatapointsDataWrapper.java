// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.api.responses.DatapointsCollection;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(description = "Times series with id and external id and the datapoints.")
public class DatapointsDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<DatapointsCollection> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<DatapointsCollection> getItems() {
        return items;
    }

}
