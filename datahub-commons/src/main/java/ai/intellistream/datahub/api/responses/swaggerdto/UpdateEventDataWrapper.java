// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.UpdateEventForm;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Update Event Collection", description = "Data Response with Events as collection.")
public class UpdateEventDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<UpdateEventForm> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<UpdateEventForm> getItems() {
        return items;
    }
}
