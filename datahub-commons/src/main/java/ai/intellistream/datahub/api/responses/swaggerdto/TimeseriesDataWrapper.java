// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.timeseries.Timeseries;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Time Series Collection", description = "Data Response with Time Series as collection.")
public class TimeseriesDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<Timeseries> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<Timeseries> getItems() {
        return items;
    }
}
