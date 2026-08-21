// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name="Timeseries Collection", description="Timeseries with data points collection")
public class DataWrapperCollectionDatapoint {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<DataCollectionDatapoint> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<DataCollectionDatapoint> getItems() {
        return items;
    }

}
