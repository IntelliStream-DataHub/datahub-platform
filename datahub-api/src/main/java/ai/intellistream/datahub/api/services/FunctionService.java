// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.validation.ResourceFields;
import ai.intellistream.datahub.function.FunctionFields;
import ai.intellistream.datahub.function.UpdateFunctionForm;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.FieldErrors;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.services.node.NodeUpdateService;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyWarning;
import ai.intellistream.datahub.transformers.NodeReadMapper;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.helpers.text.ExternalIds;
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

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A Function is a plain datastore node distinguished only by its {@code FUNCTION}
 * type-label. This service is a thin adapter over the shared write pipeline — every write
 * goes through the same dataset-ACL enforcement, optimistic locking, graph-connectivity
 * validation, and Neo4j CUD publishing that resources get. Update and delete load their
 * targets through {@link FunctionRepository} first, so they cannot reach another node type;
 * update then drives the {@code NodeUpdateService} stages itself, as {@code TimeseriesService}
 * does.
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
    private final NodeUpdateService nodeUpdateService;
    private final GraphOutbox graphOutbox;

    public FunctionService(FunctionRepository functionRepository,
                           ResourceService resourceService,
                           DataSecurity dataSecurity,
                           DatasetClosureService datasetClosureService,
                           NodeUpdateService nodeUpdateService,
                           GraphOutbox graphOutbox) {
        this.functionRepository = functionRepository;
        this.resourceService = resourceService;
        this.dataSecurity = dataSecurity;
        this.datasetClosureService = datasetClosureService;
        this.nodeUpdateService = nodeUpdateService;
        this.graphOutbox = graphOutbox;
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
     * Update functions (and any relations), the way {@code TimeseriesService} updates timeseries:
     * every target is loaded through {@link FunctionRepository}, so only {@code FUNCTION} rows can be
     * reached, and those entities are what the shared {@link NodeUpdateService} stages authorize
     * and change. A target that is not a function is a 404, as a missing one is, and the whole batch
     * fails before anything is written.
     *
     * <p>Relations still go through {@link ResourceService#update}, which owns the write check on
     * both endpoints of an edge and the dataset-ACL invalidation for {@code BELONGS_TO}.
     */
    @Transactional(rollbackFor = Exception.class)
    public GraphDataWrapper<NodeModel, EdgeProxy> update(
            GraphDataWrapper<UpdateFunctionForm, UpdateRelForm> apiReqData) throws PulsarClientException {
        Collection<UpdateFunctionForm> forms = apiReqData.getNodes();
        if (forms == null || forms.isEmpty()) {
            // Relations only: nothing typed to load, and the pipeline owns edges.
            return updateRelations(apiReqData.getRelations());
        }

        // Id when there is one, external id otherwise: the same precedence the pipeline uses.
        Set<Long> ids = forms.stream().map(UpdateFunctionForm::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> externalIds = forms.stream().filter(f -> f.getId() == null)
                .map(UpdateFunctionForm::getExternalId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<FunctionEntity> functions = functionRepository.findAllByIdOrExternalId(ids, externalIds);

        // Pass 1: pair every form with its function and authorize it, mutating nothing.
        List<NodeUpdateService.Target> targets = new ArrayList<>();
        for (UpdateFunctionForm form : forms) {
            targets.add(nodeUpdateService.authorize(asNodeCommand(form), functionFor(form, functions)));
        }

        // Pass 2: the shared stages over the whole batch — rename collisions (409), the naming
        // policy, then the field changes. Judged before applied; see NodeUpdateService.
        List<PolicyFinding> warnings;
        List<NodeEntity> updated;
        try {
            nodeUpdateService.guardRenames(targets);
            warnings = nodeUpdateService.judgeNaming(targets);
            updated = nodeUpdateService.apply(targets);
        } catch (InvalidResourceException e) {
            // e.g. a label update mixing set with add/remove: a 400, not a 500.
            throw ResourceService.toBadRequest(e);
        }
        functionRepository.flush();
        graphOutbox.queueUpsert(updated, List.of());
        resourceService.recordPolicyWarnings(warnings, updated);

        var result = new GraphDataWrapper<NodeModel, EdgeProxy>();
        result.setNodes(NodeReadMapper.from(updated));
        result.setWarnings(warnings.stream().map(PolicyWarning::from).toList());

        if (apiReqData.getRelations() != null && !apiReqData.getRelations().isEmpty()) {
            result.setRelations(updateRelations(apiReqData.getRelations()).getRelations());
        }
        return result;
    }

    /** Relations alone, through the generic pipeline: it owns edges. */
    private GraphDataWrapper<NodeModel, EdgeProxy> updateRelations(Collection<UpdateRelForm> relations)
            throws PulsarClientException {
        var relationsOnly = new GraphDataWrapper<UpdateResourceForm, UpdateRelForm>();
        relationsOnly.setRelations(relations == null ? new ArrayList<>() : new ArrayList<>(relations));
        return resourceService.update(relationsOnly);
    }

    /**
     * The command the shared stages take. The field objects are handed over as they are, so
     * {@link NodeUpdateService} validates and applies exactly what the caller sent.
     */
    private static UpdateResourceForm asNodeCommand(UpdateFunctionForm form) {
        FunctionFields update = form.getUpdate();
        ResourceFields fields = new ResourceFields();
        fields.setExternalId(update.getExternalId());
        fields.setName(update.getName());
        fields.setDescription(update.getDescription());
        fields.setDataSetId(update.getDataSetId());
        fields.setMetadata(update.getMetadata());
        fields.setSource(update.getSource());
        fields.setLabels(update.getLabels());
        return new UpdateResourceForm(form.getId()).setExternalId(form.getExternalId()).setUpdate(fields);
    }

    /** The loaded function a form names, by id when it has one and by external id otherwise. */
    private static FunctionEntity functionFor(UpdateFunctionForm form, List<FunctionEntity> functions) {
        if (form.getId() != null) {
            return functions.stream().filter(it -> form.getId().equals(it.getId())).findFirst()
                    .orElseThrow(() -> new ObjectNotFoundException("Function with id: " + form.getId() + " Not found!"));
        }
        if (form.getExternalId() != null) {
            // By hash, as the lookup matched: a raw string compare would miss a differently-cased id.
            Long hash = ExternalIds.hash(form.getExternalId());
            return functions.stream().filter(it -> hash.equals(it.getExternalIdHash())).findFirst()
                    .orElseThrow(() -> new ObjectNotFoundException(
                            "Function with externalId: " + form.getExternalId() + " Not found!"));
        }
        throw new BadRequestException("Function id or externalId is required.",
                new FieldErrors().addFieldError("id", "null").addFieldError("externalId", "null"));
    }

    /**
     * Delete one or more functions by id or externalId through the shared resource pipeline
     * (dataset-ACL, subscription, and graph-connectivity checks all apply). Ids that are not
     * functions are left alone, the same way ids that do not exist are.
     */
    @Transactional
    public void delete(DataWrapper<IdCollection> apiReqData) throws PulsarClientException {
        if (apiReqData.getItems() == null || apiReqData.getItems().isEmpty()) return;

        var graph = new GraphDataWrapper<Resource, EdgeProxy>();
        functionRepository.findAllByIdCollection(apiReqData.getItems()).forEach(entity -> {
            Resource r = new Resource();
            r.setId(entity.getId());
            graph.getNodes().add(r);
        });
        if (graph.getNodes().isEmpty()) return;
        resourceService.delete(graph);
    }
}
