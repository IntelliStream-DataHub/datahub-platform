// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.EdgeProxy;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Relationship Collection", description = "Data response with a collection of relationships (edges).")
public class EdgeDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<EdgeProxy> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<EdgeProxy> getItems() {
        return items;
    }
}
