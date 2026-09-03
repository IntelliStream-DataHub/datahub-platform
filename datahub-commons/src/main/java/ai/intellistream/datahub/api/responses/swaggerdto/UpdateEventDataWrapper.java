// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.validation.FieldLimits;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Update Event Collection", description = "Data Response with Events as collection.")
public class UpdateEventDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    @Size(max = FieldLimits.BATCH_ITEMS_MAX)
    private Collection<UpdateEventForm> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<UpdateEventForm> getItems() {
        return items;
    }
}
