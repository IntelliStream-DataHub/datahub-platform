// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.resource.RelTypeForm;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Relationship Type Form Collection", description = "Request body with the relationship types to create.")
public class RelTypeFormDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<RelTypeForm> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<RelTypeForm> getItems() {
        return items;
    }
}
