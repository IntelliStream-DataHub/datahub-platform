// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.api.mcp.dto.LeanResource;
import ai.intellistream.datahub.api.mcp.dto.LeanResourceNetwork;
import ai.intellistream.datahub.api.mcp.dto.McpList;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.helpers.updates.UpdateNumberField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.SearchForm;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.validation.ResourceFields;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generic resources — nodes in the DataHub graph that aren't timeseries or datasets
 * (equipment, work items, documents, anything else). Relationships between resources
 * are modelled as edges; see {@link EdgeMcpTools} and
 * {@link #fetchRelated(String, Long, Integer)} for graph navigation.
 *
 * <p>Creating or updating a resource *with* relationships in one call requires a
 * graph-shaped payload that doesn't fit flat tool parameters — for that, use the
 * REST API directly. These tools handle the common case: a single resource without
 * edges, plus neighbourhood discovery.
 */
@Component
@Slf4j
public class ResourceMcpTools {

    private final ResourceService resourceService;

    public ResourceMcpTools(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "resource_create",
            description = """
                    Create a single resource. A resource is any node in the DataHub graph
                    other than a dataset or timeseries — equipment, work item, document,
                    etc. At least one label is required (see label_list / label_create).
                    Relationships are not set here; use the REST API for graph-shaped
                    creates.
                    """
    )
    public GraphDataWrapper<Resource, EdgeProxy> createResource(
            @ToolParam(description = "Stable snake_case id. 3–256 chars.")
            String externalId,
            @ToolParam(description = "Human-readable display name.")
            String name,
            @ToolParam(description = "Comma-separated label names (e.g. 'PUMP,CRITICAL'). At least one.")
            String labels,
            @ToolParam(required = false, description = "Optional description.")
            String description,
            @ToolParam(required = false, description = "Id of the owning dataset.")
            Long dataSetId
    ) throws Exception {
        Resource r = new Resource();
        r.setExternalId(externalId);
        r.setName(name);
        if (description != null) r.setDescription(description);
        if (dataSetId != null) r.setDataSetId(dataSetId);

        List<String> labelList = Arrays.stream(labels.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (labelList.isEmpty()) {
            throw new IllegalArgumentException("At least one label is required");
        }
        r.setLabels(new ArrayList<>(labelList));

        GraphDataWrapper<Resource, RelForm> req = new GraphDataWrapper<>();
        req.getNodes().add(r);
        return resourceService.create(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "resource_get",
            description = """
                    Fetch one or more resources by id or externalId. Supply at least one
                    of the two collections (as comma-separated strings).
                    """
    )
    public DataWrapper<NodeModel> getResource(
            @ToolParam(required = false, description = "Comma-separated numeric ids.")
            String ids,
            @ToolParam(required = false, description = "Comma-separated externalIds.")
            String externalIds
    ) {
        Set<Long> idSet = (ids == null || ids.isBlank())
                ? Set.of()
                : java.util.Arrays.stream(ids.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Long::parseLong).collect(Collectors.toSet());
        Set<String> extSet = (externalIds == null || externalIds.isBlank())
                ? Set.of()
                : java.util.Arrays.stream(externalIds.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
        if (idSet.isEmpty() && extSet.isEmpty()) {
            throw new IllegalArgumentException("Supply at least one id or externalId");
        }
        return resourceService.findAllByIdAndExternalId(idSet, extSet);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "resource_search",
            description = """
                    Full-text search across every node type — assets, timeseries,
                    functions, resources, datasets and policies — for those whose
                    name, externalId or description matches the query. Ranked by
                    relevance, best match first. Capped by 'limit' (default 100, max 1000).
                    """
    )
    public McpList<LeanResource> searchResources(
            @ToolParam(description = "Search text.")
            String query,
            @ToolParam(required = false, description = "Max results (default 100, max 1000).")
            Integer limit
    ) {
        int cap = (limit == null) ? 100 : limit;
        SearchBody<ResourceFilter> s = new SearchBody<>();
        SearchForm sf = new SearchForm();
        sf.setQuery(query);
        s.setSearch(sf);
        s.setLimit(cap);
        List<LeanResource> items = resourceService.search(s).getItems().stream()
                .map(LeanResource::from).toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "resource_update",
            description = """
                    Update common fields on a single resource. Identify by id. Only
                    provided fields are set. Edge updates and metadata add/remove
                    aren't exposed here — use the REST API for those.
                    """
    )
    public GraphDataWrapper<Resource, EdgeProxy> updateResource(
            @ToolParam(description = "Id of the resource to update.")
            Long id,
            @ToolParam(required = false, description = "New display name.")
            String newName,
            @ToolParam(required = false, description = "New description.")
            String newDescription,
            @ToolParam(required = false, description = "New snake_case externalId.")
            String newExternalId,
            @ToolParam(required = false, description = "New owning dataset id.")
            Long newDataSetId
    ) throws Exception {
        UpdateResourceForm form = new UpdateResourceForm(id);
        ResourceFields f = new ResourceFields();
        if (newName != null) f.setName(new UpdateStringField().set(newName));
        if (newDescription != null) f.setDescription(new UpdateStringField().set(newDescription));
        if (newExternalId != null) f.setExternalId(new UpdateStringField().set(newExternalId));
        if (newDataSetId != null) f.setDataSetId(new UpdateNumberField().set(newDataSetId));
        form.setUpdate(f);

        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> req = new GraphDataWrapper<>();
        req.getNodes().add(form);
        return resourceService.update(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "resource_delete",
            description = """
                    Delete a single resource by id or externalId. Connected edges are
                    removed with the node; adjacent resources are not touched.
                    """
    )
    public String deleteResource(
            @ToolParam(required = false, description = "Target resource id.")
            Long id,
            @ToolParam(required = false, description = "Target resource externalId.")
            String externalId
    ) throws Exception {
        if ((id == null) == (externalId == null)) {
            throw new IllegalArgumentException("Supply exactly one of id or externalId");
        }
        Resource r = new Resource();
        if (id != null) r.setId(id);
        else r.setExternalId(externalId);
        GraphDataWrapper<Resource, EdgeProxy> req = new GraphDataWrapper<>();
        req.getNodes().add(r);
        resourceService.delete(req);
        return "deleted";
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "resource_fetch_related",
            description = """
                    Walk the graph outward from one resource and return its connected
                    neighbourhood — every reachable resource plus the relationships between them.
                    Defaults to 2 hops to keep the payload bounded; pass a larger `depth` to
                    reach further, or -1 to load the whole connected component (can be large).
                    Useful for exploring connections between equipment, datasets, events, etc.
                    """
    )
    public LeanResourceNetwork fetchRelated(
            @ToolParam(required = false, description = "Target resource externalId.")
            String externalId,
            @ToolParam(required = false, description = "Target resource id.")
            Long id,
            @ToolParam(required = false, description = "Max hops to traverse. Default 2; a larger value reaches further; -1 loads the whole connected component.")
            Integer depth
    ) {
        if ((externalId == null) == (id == null)) {
            throw new IllegalArgumentException("Supply exactly one of externalId or id");
        }
        RelatedResourcesForm form = new RelatedResourcesForm();
        if (id != null) form.setId(id);
        if (externalId != null) form.setExternalId(externalId);
        form.setDepth(depth == null ? 2 : depth);
        return LeanResourceNetwork.from(resourceService.fetchRelatedResources(form));
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "resource_fetch_nearest",
            description = """
                    Breadth-first from a starting resource, return the closest `limit` nodes
                    carrying one of `endLabels` (e.g. 'TIMESERIES') plus the sub-graph connecting
                    them. The cap is on matching END-nodes, not hop depth — "the 10 nearest time
                    series" is exact however many intermediate nodes lie between. Prefer this over
                    resource_fetch_related when looking for a specific kind of neighbour, e.g. the
                    time series measuring a pump or the assets serving a function.
                    """
    )
    public LeanResourceNetwork fetchNearest(
            @ToolParam(required = false, description = "Starting resource externalId.")
            String externalId,
            @ToolParam(required = false, description = "Starting resource id.")
            Long id,
            @ToolParam(description = "Comma-separated node labels that qualify as an end-node (e.g. 'TIMESERIES').")
            String endLabels,
            @ToolParam(required = false, description = "Max matching end-nodes to return (default 10).")
            Integer limit,
            @ToolParam(required = false, description = "Comma-separated relationship types the traversal may follow (default: all).")
            String relationshipTypes,
            @ToolParam(required = false, description = "Comma-separated node labels the traversal never passes through or returns.")
            String excludedLabels
    ) {
        if ((externalId == null) == (id == null)) {
            throw new IllegalArgumentException("Supply exactly one of externalId or id");
        }
        List<String> ends = splitCsv(endLabels);
        if (ends.isEmpty()) {
            throw new IllegalArgumentException("Supply at least one end label");
        }
        Long startId = id != null ? id : resolveId(externalId);
        return LeanResourceNetwork.from(resourceService.fetchNearestRelatedResources(
                startId, ends,
                limit == null ? 10 : limit,
                splitCsv(relationshipTypes),
                splitCsv(excludedLabels)));
    }

    /** The nearest-N service method takes a numeric id; resolve an externalId the same way byids does. */
    private Long resolveId(String externalId) {
        return resourceService.findAllByIdAndExternalId(Set.of(), Set.of(externalId))
                .getItems().stream()
                .map(NodeModel::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No resource with externalId '" + externalId + "'"));
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
