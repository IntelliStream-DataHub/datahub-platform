// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.FunctionRetreiver;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.datafilters.FunctionFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.repositories.node.FunctionRepository;
import ai.intellistream.datahub.repositories.node.NodeSort;
import ai.intellistream.datahub.transformers.FunctionTransformer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A Function is a plain datastore node distinguished only by its {@code FUNCTION}
 * type-label. This service is a thin adapter over the shared {@link ResourceService}
 * pipeline — every write goes through the same dataset-ACL enforcement, optimistic
 * locking, graph-connectivity validation, and Neo4j CUD publishing that resources get.
 * The {@code FUNCTION} label (always present on {@link Function}) is what makes
 * {@code NodeService.createFromResource} build a {@code FunctionEntity} rather than a
 * plain resource.
 */
@Service
@Slf4j
public class FunctionService {

    private final FunctionRepository functionRepository;
    private final ResourceService resourceService;
    private final DataSecurity dataSecurity;
    private final DatasetClosureService datasetClosureService;

    public FunctionService(FunctionRepository functionRepository,
                           ResourceService resourceService,
                           DataSecurity dataSecurity,
                           DatasetClosureService datasetClosureService) {
        this.functionRepository = functionRepository;
        this.resourceService = resourceService;
        this.dataSecurity = dataSecurity;
        this.datasetClosureService = datasetClosureService;
    }

    /**
     * Create one or more functions through the shared resource pipeline. The response is
     * re-read from Postgres as {@link Function} DTOs so callers keep the function-shaped
     * response.
     */
    @Transactional
    public DataWrapper<Function> create(DataWrapper<Function> apiReqData) throws PulsarClientException {
        var graph = new GraphDataWrapper<NodeModel, RelForm>();
        // Straight through: the pipeline takes NodeModel and Function is one, so its FUNCTION
        // type-label drives the dispatch with nothing copied by hand — a field added to Function
        // later cannot be silently dropped on the way in.
        // Timestamps need no handling here: the create pipeline does not read them off the body
        // at all, so Hibernate's generated values stand. (This used to null them defensively,
        // which the DTO's setter quietly turned back into now().)
        graph.setNodes(new ArrayList<>(apiReqData.getItems()));
        graph.setRelations(new ArrayList<>());

        GraphDataWrapper<NodeModel, EdgeProxy> created = resourceService.create(graph);

        List<Long> ids = created.getNodes().stream()
                .map(NodeModel::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        var result = new DataWrapper<Function>();
        result.setItems(new ArrayList<>(FunctionTransformer.toFunction(functionRepository.findAllById(ids))));
        // Same reason as the data set adapter: the shared path judged the name, and re-wrapping
        // the response would otherwise swallow what it found.
        result.setWarnings(created.getWarnings());
        return result;
    }

    /**
     * One function by id.
     *
     * <p>The last gap in the node family's endpoint surface: every other type could be fetched by
     * id without POSTing a wrapper, and {@code NodeFamilyParityTest} carried an explicit exemption
     * for this one. A function the caller may not read is reported as missing rather than
     * forbidden, the same way {@code ResourceService.get} hides existence.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Function> get(Long id) {
        FunctionEntity entity = functionRepository.findById(id)
                .filter(f -> dataSecurity.hasReadPermissionToDataSet(f))
                .orElseThrow(() -> new ObjectNotFoundException("Function with id: " + id + " Not found!"));

        DataWrapper<Function> data = new DataWrapper<>();
        data.getItems().add(FunctionTransformer.from(entity));
        return data;
    }

    /**
     * Functions by id and/or external id. Ids that do not exist, are not functions, or are not
     * readable by this caller are left out rather than failing the call — the same reading every
     * other {@code /byids} on a node type gives.
     *
     * <p>The repository is typed, so "is not a function" needs no check here: a node of another
     * type is simply not a row this query returns.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Function> byIds(Set<Long> idList, Set<String> externalIdList) {
        List<FunctionEntity> entities =
                narrowToReadable(functionRepository.findAllByIdOrExternalId(idList, externalIdList));

        var data = new DataWrapper<Function>();
        data.setItems(new ArrayList<>(FunctionTransformer.toFunction(entities)));
        return data;
    }

    /**
     * Structured AND-combined function filter, the function counterpart to
     * {@code ResourceService.filter} and {@code TimeseriesService.filter}.
     *
     * <p>The query pins the {@code FUNCTION} discriminator itself rather than narrowing a mixed
     * result afterwards, so paging counts what the caller asked for: a page of mixed nodes trimmed
     * after the fact would return fewer items than the page size and a cursor that skips the
     * difference.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Function> filter(FunctionRetreiver apiReqData) {
        NodeSort sort = NodeSort.resolve(apiReqData.getSort());
        PageCursor cursor = FilterPaging.validated(apiReqData.getCursor(), sort);

        List<FunctionEntity> entities = functionRepository.filter(
                apiReqData.getLimit(), visibleDataSetScope(apiReqData.getFilter()),
                apiReqData.getFilter(), sort, cursor);

        var data = new DataWrapper<Function>();
        data.setItems(new ArrayList<>(FunctionTransformer.toFunction(entities)));
        data.setNextCursor(FilterPaging.nextCursor(entities, apiReqData.getLimit(), sort));
        return data;
    }

    /**
     * Full-text search over functions, optionally narrowed by a {@link FunctionFilter}. One query:
     * the phrase is a predicate beside the filter's, not a separate pass.
     *
     * <p>An omitted filter narrows nothing and is not a way to widen the endpoint: the discriminator
     * lives in the query, not in the filter, so a body with no {@code filter} block still answers
     * with functions only. No match is a normal empty result, not a 404.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Function> search(SearchBody<FunctionFilter> searchForm) {
        List<FunctionEntity> entities = functionRepository.search(
                searchForm.getSearch().getQuery(), searchForm.getLimit(),
                visibleDataSetScope(searchForm.getFilter()), searchForm.getFilter());

        var data = new DataWrapper<Function>();
        data.setItems(new ArrayList<>(FunctionTransformer.toFunction(entities)));
        return data;
    }

    /**
     * The data set scope a filtered read runs under: {@code null} when nothing restricts it,
     * otherwise the data sets the caller may read, narrowed by whatever the filter asked for.
     * Empty means "nothing visible here" and the repository returns without querying.
     *
     * <p>A data set stands in for everything beneath it in the {@code BELONGS_TO} hierarchy, the
     * same way a grant on it does — resolved through {@link DatasetClosureService}, the component
     * that expands the grants themselves, so {@code dataSetId=X} covers exactly what a grant on X
     * would. Filtering on a parent therefore finds its children.
     */
    private Set<Long> visibleDataSetScope(FunctionFilter filter) {
        Set<Long> allowed = dataSecurity.hasReadAccessToEverything()
                ? null
                : dataSecurity.readableDataSetIds();
        if (allowed != null && allowed.isEmpty()) {
            return Set.of();
        }
        if (filter == null || filter.getDataSetId() == null) {
            return allowed;
        }
        Set<Long> requested = datasetClosureService.closureOfReferences(filter.getDataSetId());
        // Empty falls through as "nothing visible": they asked to be narrowed to data sets that
        // resolve to nothing they can read, and dropping the restriction would widen the query to
        // everything instead.
        return allowed == null
                ? requested
                : requested.stream().filter(allowed::contains).collect(Collectors.toSet());
    }

    /**
     * Narrow rows to what the caller may read, by asking {@link DataSecurity} rather than deciding
     * here. It is the same check {@link #get} makes, so a function cannot be listed and then 404 on
     * its own endpoint.
     *
     * <p>That matters for the orphan case specifically. A function with no data set cannot be
     * matched against a data-set ACL at all, so only an all-datasets reader may see one — the rule
     * {@code DataSecurity} states and {@code NodePredicateBuilder.dataSetScope} enforces in SQL for
     * {@link #filter} and {@link #search}, whose inner join on {@code dataSet} drops orphans. This
     * used to be spelled out inline as "readable, or no data set at all", which showed every orphan
     * function to every caller and disagreed with the other four reads of the same rows.
     */
    private List<FunctionEntity> narrowToReadable(List<FunctionEntity> entities) {
        if (dataSecurity.hasReadAccessToEverything()) {
            return entities;
        }
        return entities.stream()
                .filter(dataSecurity::hasReadPermissionToDataSet)
                .toList();
    }

    /**
     * The newest {@code limit} functions the caller may read. No criteria — anything narrower
     * belongs in {@link #filter} or {@link #search}.
     *
     * <p>Capped since it grew a {@code limit}: it used to return every row in the tenant, which is
     * a response whose size the caller cannot bound and the server cannot predict. Small-by-design
     * is not the same as small, and the two node types with no cap were the two nobody had
     * revisited.
     *
     * <p>The cap is applied <em>after</em> the dataset ACL, not in the query. Truncating first
     * would let a caller with narrow grants see fewer functions than they are entitled to while
     * more readable ones sat past the cut — the ordering is by creation, not by grant. Filtering
     * the whole (small) inventory and then taking the newest {@code limit} keeps "the newest N you
     * may read" true.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Function> list(int limit) {
        List<FunctionEntity> entities =
                functionRepository.findAll(Sort.by(Sort.Direction.DESC, "dateCreated"));

        // Narrow to what the caller may read, like every other node read. Create/update/delete get
        // their dataset ACL from the shared ResourceService pipeline, but list() queries the
        // repository directly, so it was returning every function on the tenant regardless of
        // grants — and then, once narrowed, kept showing orphans to callers who may not read them.
        entities = narrowToReadable(entities);

        if (entities.size() > limit) {
            entities = entities.subList(0, limit);
        }

        var results = new DataWrapper<Function>();
        results.setItems(new ArrayList<>(FunctionTransformer.toFunction(entities)));
        return results;
    }

    /**
     * Update functions (and any relations) through the shared resource pipeline. Functions
     * are editable like resources; the intrinsic {@code FUNCTION} type-label stays immutable
     * via the label-resolution rules in {@link ResourceService}.
     */
    @Transactional
    public GraphDataWrapper<NodeModel, EdgeProxy> update(
            GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData) throws PulsarClientException {
        return resourceService.update(apiReqData);
    }

    /**
     * Delete one or more functions by id or externalId through the shared resource pipeline
     * (dataset-ACL, subscription, and graph-connectivity checks all apply).
     */
    @Transactional
    public void delete(DataWrapper<IdCollection> apiReqData) throws PulsarClientException {
        if (apiReqData.getItems() == null || apiReqData.getItems().isEmpty()) return;

        var graph = new GraphDataWrapper<Resource, EdgeProxy>();
        apiReqData.getItems().forEach(it -> {
            Resource r = new Resource();
            if (it.getId() != null) {
                r.setId(it.getId());
                graph.getNodes().add(r);
            } else if (it.getExternalId() != null) {
                r.setExternalId(it.getExternalId());
                graph.getNodes().add(r);
            }
        });
        resourceService.delete(graph);
    }
}
