// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;


public class UUIDAndExternalIdCollectionDataWrapper {

    @Schema(description = "Add either id or external id or a mix of both to the request body.")
    @Size(max = 10000)
    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<UUIDAndExternalIdCollection> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<UUIDAndExternalIdCollection> getItems() {
        return items;
    }

}
