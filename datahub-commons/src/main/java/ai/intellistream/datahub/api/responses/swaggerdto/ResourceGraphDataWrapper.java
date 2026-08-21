// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "GraphResources", description = "Resources and Relations for graph data network.")
public class ResourceGraphDataWrapper {

    @Size(max = 1000)
    private Collection<Resource> nodes = new ArrayList<>();

    @Size(max = 1000)
    private Collection<EdgeProxy> relations = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<Resource> getNodes() {
        return nodes;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<EdgeProxy> getRelations() {
        return relations;
    }
}
