// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.resource.ResourceForm;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "CreateResources", description = "CreateResources for graph data network.")
public class CreateResources {

    @Size(max = 1000)
    private Collection<ResourceForm> nodes = new ArrayList<>();

    @Size(max = 1000)
    private Collection<RelForm> relations = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<ResourceForm> getNodes() {
        return nodes;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<RelForm> getRelations() {
        return relations;
    }

}