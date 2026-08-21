// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

public class UpdateTimeseriesWrapper {

    @Schema(description = "Update timeseries request body")
    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<UpdateTimeseries> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<UpdateTimeseries> getItems() {
        return items;
    }

}
