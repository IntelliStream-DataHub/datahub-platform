// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.RelForm;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Relationship Form Collection", description = "Request body with the relationships (edges) to create.")
public class RelFormDataWrapper {

    @Schema(description = "Identify each endpoint by id or externalId. Both resources must already exist.")
    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<RelForm> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<RelForm> getItems() {
        return items;
    }
}
