// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.DeleteDatapoint;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name="Delete Datapoint Collection", description="The list of delete requests to perform.")
public class DeleteDatapointCollection {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<DeleteDatapoint> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<DeleteDatapoint> getItems() {
        return items;
    }
}
