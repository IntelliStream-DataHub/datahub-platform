// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.api.mcp.dto.LeanRelationshipType;
import ai.intellistream.datahub.api.mcp.dto.McpList;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.services.EdgeService;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.resource.RelTypeForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Edges and relationship types.
 *
 * <p>{@link #createEdge(String, Long, String, Long, String, String)} handles the
 * common case of linking two nodes that already exist. Creating nodes and edges
 * atomically in one call needs the full graph-wrapper shape and still goes
 * through the REST API.
 */
@Component
@Slf4j
public class EdgeMcpTools {

    private final EdgeService edgeService;
    private final ResourceService resourceService;
    private final RelationshipTypeRepository relationshipTypeRepository;

    public EdgeMcpTools(
            EdgeService edgeService,
            ResourceService resourceService,
            RelationshipTypeRepository relationshipTypeRepository
    ) {
        this.edgeService = edgeService;
        this.resourceService = resourceService;
        this.relationshipTypeRepository = relationshipTypeRepository;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "edge_create",
            description = """
                    Create a single edge between two existing nodes. Identify each
                    endpoint by externalId or numeric id — pass either, not both.
                    The relationshipType must already exist (see edge_list_types
                    and edge_create_type).
                    """
    )
    public GraphDataWrapper<NodeModel, EdgeProxy> createEdge(
            @ToolParam(required = false, description = "Source node externalId.")
            String fromExternalId,
            @ToolParam(required = false, description = "Source node id.")
            Long fromId,
            @ToolParam(required = false, description = "Target node externalId.")
            String toExternalId,
            @ToolParam(required = false, description = "Target node id.")
            Long toId,
            @ToolParam(description = "Relationship type name, e.g. 'PROCESSED_BY'.")
            String relationshipType,
            @ToolParam(required = false, description = "Optional description.")
            String description
    ) throws Exception {
        if ((fromExternalId == null) == (fromId == null)) {
            throw new IllegalArgumentException("Supply exactly one of fromExternalId or fromId");
        }
        if ((toExternalId == null) == (toId == null)) {
            throw new IllegalArgumentException("Supply exactly one of toExternalId or toId");
        }
        RelForm rel = new RelForm();
        if (fromExternalId != null) rel.setFromExternalId(fromExternalId);
        if (fromId != null) rel.setFromId(fromId);
        if (toExternalId != null) rel.setToExternalId(toExternalId);
        if (toId != null) rel.setToId(toId);
        rel.setRelationshipType(relationshipType);
        if (description != null) rel.setDescription(description);

        GraphDataWrapper<NodeModel, RelForm> req = new GraphDataWrapper<>();
        req.getRelations().add(rel);
        return resourceService.create(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "edge_get",
            description = "Fetch a single edge by its numeric id."
    )
    public DataWrapper<EdgeProxy> getEdge(
            @ToolParam(description = "Edge id.")
            Long id
    ) {
        return edgeService.findById(id);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "edge_list_types",
            description = """
                    List relationship types defined for the tenant. Each type has a name
                    (e.g. 'PROCESSED_BY', 'DERIVED_FROM') that resources use to describe how
                    they're connected in the graph. The set is small; 'truncated' is set on the
                    rare overflow.
                    """
    )
    public McpList<LeanRelationshipType> listRelationshipTypes(
            @ToolParam(required = false, description = "Max results (default 500).")
            Integer limit
    ) {
        int cap = (limit == null) ? 500 : limit;
        List<LeanRelationshipType> items = relationshipTypeRepository.findAll().stream()
                .map(LeanRelationshipType::from)
                .limit(cap)
                .toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "edge_create_type",
            description = """
                    Create a new relationship type. Name is coerced to
                    SCREAMING_SNAKE_CASE server-side, so 'derived from' becomes
                    'DERIVED_FROM'.
                    """
    )
    public DataWrapper<RelationshipType> createRelationshipType(
            @ToolParam(description = "Human-readable name; normalised to SCREAMING_SNAKE_CASE.")
            String name,
            @ToolParam(required = false, description = "Optional description.")
            String description,
            @ToolParam(required = false, description = "Optional i18n code.")
            String i18nCode
    ) {
        RelTypeForm form = new RelTypeForm();
        form.setName(name);
        if (description != null) form.setDescription(description);
        if (i18nCode != null) form.setI18nCode(i18nCode);

        DataWrapper<RelTypeForm> req = new DataWrapper<>();
        req.setItems(List.of(form));
        DataWrapper<RelationshipType> out = new DataWrapper<>();
        out.setItems(new ArrayList<>(edgeService.createRelationshipTypes(req)));
        return out;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "edge_delete",
            description = """
                    Delete one or more edges by id. Does not cascade to the nodes on
                    either side — only the relationship is removed.
                    """
    )
    public String deleteEdges(
            @ToolParam(description = "Edge id to delete.")
            Long id
    ) throws Exception {
        IdCollection ref = new IdCollection();
        ref.setId(id);
        DataWrapper<IdCollection> req = new DataWrapper<>();
        req.getItems().add(ref);
        edgeService.deleteRelationships(req);
        return "deleted";
    }
}
