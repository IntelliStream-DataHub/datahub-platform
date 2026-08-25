// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.api.mcp.dto.LeanDataSet;
import ai.intellistream.datahub.api.mcp.dto.McpList;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.api.services.DataSetService;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.SearchForm;
import ai.intellistream.datahub.models.forms.DataSetFields;
import ai.intellistream.datahub.models.forms.DataSetForm;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.PolicyRepository;
import ai.intellistream.datahub.transformers.DataSetTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Slf4j
public class DatasetMcpTools {

    private final DataSetService dataSetService;
    private final ResourceService resourceService;
    private final PolicyRepository policyRepository;
    private final DataSetRepository dataSetRepository;

    public DatasetMcpTools(
            DataSetService dataSetService,
            ResourceService resourceService,
            PolicyRepository policyRepository,
            DataSetRepository dataSetRepository
    ) {
        this.dataSetService = dataSetService;
        this.resourceService = resourceService;
        this.policyRepository = policyRepository;
        this.dataSetRepository = dataSetRepository;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "dataset_search",
            description = """
                    Full-text search for datasets. Returns matching datasets including
                    their ids (needed for timeseries_create). Query is matched against
                    name, externalId and description, ranked by relevance.
                    """
    )
    public McpList<LeanDataSet> searchDatasets(
            @ToolParam(description = "Search text.")
            String query,
            @ToolParam(required = false, description = "Max results (default 100, max 1000).")
            Integer limit
    ) {
        int cap = (limit == null) ? 100 : limit;
        SearchBody<DataSetFilter> req = new SearchBody<>();
        var sf = new SearchForm();
        sf.setQuery(query);
        req.setSearch(sf);
        req.setLimit(cap);
        List<LeanDataSet> items = dataSetService.search(req).getItems().stream()
                .map(LeanDataSet::from).toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "dataset_create",
            description = """
                    Create a single dataset. Returns the created dataset including
                    the server-assigned id, which timeseries_create needs. The
                    externalId is a stable, snake_case identifier (e.g. 'sap_work_orders').
                    """
    )
    public DataWrapper<DataSetModel> createDataset(
            @ToolParam(description = "Stable snake_case id. 3–256 chars, letters/digits/underscore.")
            String externalId,
            @ToolParam(description = "Human-readable display name.")
            String name,
            @ToolParam(required = false, description = "Optional description.")
            String description
    ) throws Exception {
        DataSetModel ds = new DataSetModel();
        ds.setExternalId(externalId);
        ds.setName(name);
        if (description != null) ds.setDescription(description);

        List<PolicyEntity> policies = policyRepository.findAll();
        GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, RelForm> graph =
                DataSetTransformer.toGraphForm(List.of(ds), policies, List.of());
        var results = resourceService.create(graph);

        DataWrapper<DataSetModel> out = new DataWrapper<>();
        out.setItems(DataSetTransformer.toDataSetModel(results.getNodes()));
        return out;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "dataset_update",
            description = """
                    Update a single dataset. Identify by id. Only provided fields are
                    changed. For niche patch operations (metadata add/remove), use the REST
                    API directly.
                    """
    )
    public DataWrapper<DataSetModel> updateDataset(
            @ToolParam(description = "Id of the dataset to update.")
            Long id,
            @ToolParam(required = false, description = "New display name.")
            String newName,
            @ToolParam(required = false, description = "New description.")
            String newDescription,
            @ToolParam(required = false, description = "New snake_case externalId.")
            String newExternalId
    ) throws Exception {
        DataSetForm form = new DataSetForm();
        form.setId(id);
        DataSetFields fields = new DataSetFields();
        if (newName != null) fields.setName(new UpdateStringField().set(newName));
        if (newDescription != null) fields.setDescription(new UpdateStringField().set(newDescription));
        if (newExternalId != null) fields.setExternalId(new UpdateStringField().set(newExternalId));
        form.setUpdate(fields);

        DataWrapper<DataSetForm> req = new DataWrapper<>();
        req.getItems().add(form);
        return dataSetService.update(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "dataset_list",
            description = """
                    List datasets visible to the caller. The catalogue is small (typical size
                    ~dozens to low hundreds), so 'limit' rarely bites; when it does, 'truncated'
                    is set so you know to narrow with dataset_search instead.
                    """
    )
    @Transactional(readOnly = true)
    public McpList<LeanDataSet> listDatasets(
            @ToolParam(required = false, description = "Max results (default 200).")
            Integer limit
    ) {
        int cap = (limit == null) ? 200 : limit;
        List<LeanDataSet> items = DataSetTransformer
                .toDataSetModel(ResourceTransformer.from(dataSetRepository.findAll())).stream()
                .map(LeanDataSet::from)
                .limit(cap)
                .toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "dataset_delete",
            description = """
                    Delete a single dataset by id or externalId. Does not cascade to
                    member resources/timeseries — those must be removed first or
                    reassigned, otherwise the delete will fail.
                    """
    )
    public String deleteDataset(
            @ToolParam(required = false, description = "Target dataset id.")
            Long id,
            @ToolParam(required = false, description = "Target dataset externalId.")
            String externalId
    ) throws Exception {
        if ((id == null) == (externalId == null)) {
            throw new IllegalArgumentException("Supply exactly one of id or externalId");
        }
        GraphDataWrapper<Resource, EdgeProxy> graph = new GraphDataWrapper<>();
        Resource r = new Resource();
        if (id != null) r.setId(id);
        else r.setExternalId(externalId);
        graph.getNodes().add(r);
        resourceService.delete(graph);
        return "deleted";
    }
}
