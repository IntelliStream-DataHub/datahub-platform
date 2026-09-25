// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.validation.ResourceFields;
import ai.intellistream.datahub.models.AssetFields;
import ai.intellistream.datahub.models.UpdateAssetForm;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.FieldErrors;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.services.node.NodeUpdateService;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyWarning;
import ai.intellistream.datahub.transformers.NodeReadMapper;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import ai.intellistream.datahub.repositories.node.AssetRepository;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The typed endpoint family for assets, the way {@code /timeseries}, {@code /datasets},
 * {@code /policies} and {@code /functions} have one.
 *
 * <p>Every operation is the shared {@link ResourceService} pipeline with the {@code ASSET}
 * discriminator pinned — nothing here decides anything the generic {@code /resources} path does
 * not. Reads come back already typed: the read mapper builds an {@link Asset} for an
 * {@code AssetEntity}, so this narrows rather than converts, and a node of some other type is
 * simply not an asset and is reported as missing.
 *
 * <p>The writes are the exception to "nothing decided here": the pipeline resolves its targets as
 * plain nodes, so update and delete load them through {@link AssetRepository} instead. Update then
 * drives the shared {@code NodeUpdateService} stages itself, as {@code TimeseriesService} does;
 * delete hands the loaded ids to the pipeline. Without that, {@code /assets/delete} would delete a
 * timeseries and {@code /assets/update} would edit one.
 */
@Service
public class AssetService {

    private final ResourceService resourceService;
    private final AssetRepository assetRepository;
    private final NodeUpdateService nodeUpdateService;
    private final GraphOutbox graphOutbox;

    public AssetService(ResourceService resourceService, AssetRepository assetRepository,
                        NodeUpdateService nodeUpdateService, GraphOutbox graphOutbox) {
        this.resourceService = resourceService;
        this.assetRepository = assetRepository;
        this.nodeUpdateService = nodeUpdateService;
        this.graphOutbox = graphOutbox;
    }

    /**
     * Create assets through the shared pipeline.
     *
     * <p>The bodies go straight in: the pipeline takes {@link NodeModel}, {@link Asset} is one, and
     * its {@code ASSET} type-label drives the dispatch — so nothing is copied by hand and a field
     * added to {@code Asset} later cannot be silently dropped on the way in. The echo is already
     * {@code Asset}-shaped for the same reason.
     */
    @Transactional
    public DataWrapper<Asset> create(DataWrapper<Asset> apiReqData) throws PulsarClientException {
        var graph = new GraphDataWrapper<NodeModel, RelForm>();
        graph.setNodes(new ArrayList<>(apiReqData.getItems()));
        graph.setRelations(new ArrayList<>());

        GraphDataWrapper<NodeModel, EdgeProxy> created = resourceService.create(graph);

        var result = onlyAssets(created.getNodes());
        // The pipeline judged these names; re-wrapping would otherwise swallow what it found.
        result.setWarnings(created.getWarnings());
        return result;
    }

    /** One asset by id. A node of another type is reported as missing, not as a type error. */
    @Transactional(readOnly = true)
    public DataWrapper<Asset> get(Long id) {
        DataWrapper<Asset> data = onlyAssets(resourceService.get(id).getItems());
        if (data.getItems().isEmpty()) {
            throw new ObjectNotFoundException("Asset with id: " + id + " Not found!");
        }
        return data;
    }

    /** Assets by id and/or external id, narrowed the same way. */
    @Transactional(readOnly = true)
    public DataWrapper<Asset> byIds(Set<Long> idList, Set<String> externalIdList) {
        return onlyAssets(resourceService.findAllByIdAndExternalId(idList, externalIdList).getItems());
    }

    /**
     * Filter assets. The type is pinned on the way in rather than filtered on the way out, so
     * paging counts what the caller asked for: a page of mixed nodes trimmed afterwards would
     * return fewer items than the page size and a cursor that skips the difference.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Asset> filter(ResourceRetreiver apiReqData) {
        pinAssetType(apiReqData.getFilter());
        DataWrapper<NodeModel> page = resourceService.filter(apiReqData);
        return onlyAssets(page.getItems()).setNextCursor(page.getNextCursor());
    }

    /**
     * Free-text search, with the same type pinning as {@link #filter}.
     *
     * <p>A search body may legally omit the filter — {@code SearchBody.filter} has no default, where
     * {@code ResourceRetreiver.filter} does — and there would then be nothing to pin the type on.
     * The query would run across every node type, the limit would be spent on that mixed page, and
     * the narrowing below would trim it, answering a request for N assets with however few of them
     * happened to rank in the first N nodes. Give it a filter to carry the type.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Asset> search(SearchBody<ResourceFilter> searchForm) {
        if (searchForm.getFilter() == null) {
            searchForm.setFilter(new ResourceFilter());
        }
        pinAssetType(searchForm.getFilter());
        DataWrapper<NodeModel> page = resourceService.search(searchForm);
        return onlyAssets(page.getItems()).setNextCursor(page.getNextCursor());
    }

    /**
     * Update assets (and any relations), the way {@code TimeseriesService} updates timeseries:
     * every target is loaded through {@link AssetRepository}, so only {@code ASSET} rows can be
     * reached, and those entities are what the shared {@link NodeUpdateService} stages authorize
     * and change. A target that is not an asset is a 404, as a missing one is, and the whole batch
     * fails before anything is written.
     *
     * <p>Relations still go through {@link ResourceService#update}, which owns the write check on
     * both endpoints of an edge and the dataset-ACL invalidation for {@code BELONGS_TO}.
     */
    @Transactional(rollbackFor = Exception.class)
    public GraphDataWrapper<NodeModel, EdgeProxy> update(
            GraphDataWrapper<UpdateAssetForm, UpdateRelForm> apiReqData) throws PulsarClientException {
        Collection<UpdateAssetForm> forms = apiReqData.getNodes();
        if (forms == null || forms.isEmpty()) {
            // Relations only: nothing typed to load, and the pipeline owns edges.
            return updateRelations(apiReqData.getRelations());
        }

        // Id when there is one, external id otherwise: the same precedence the pipeline uses.
        Set<Long> ids = forms.stream().map(UpdateAssetForm::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> externalIds = forms.stream().filter(f -> f.getId() == null)
                .map(UpdateAssetForm::getExternalId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<AssetEntity> assets = assetRepository.findAllByIdOrExternalId(ids, externalIds);

        // Pass 1: pair every form with its asset and authorize it, mutating nothing.
        List<NodeUpdateService.Target> targets = new ArrayList<>();
        for (UpdateAssetForm form : forms) {
            targets.add(nodeUpdateService.authorize(asNodeCommand(form), assetFor(form, assets)));
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
        assetRepository.flush();
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
    private static UpdateResourceForm asNodeCommand(UpdateAssetForm form) {
        AssetFields update = form.getUpdate();
        ResourceFields fields = new ResourceFields();
        fields.setExternalId(update.getExternalId());
        fields.setName(update.getName());
        fields.setDescription(update.getDescription());
        fields.setDataSetId(update.getDataSetId());
        fields.setMetadata(update.getMetadata());
        fields.setSource(update.getSource());
        fields.setLabels(update.getLabels());
        fields.setGeoLocation(update.getGeoLocation());
        return new UpdateResourceForm(form.getId()).setExternalId(form.getExternalId()).setUpdate(fields);
    }

    /** The loaded asset a form names, by id when it has one and by external id otherwise. */
    private static AssetEntity assetFor(UpdateAssetForm form, List<AssetEntity> assets) {
        if (form.getId() != null) {
            return assets.stream().filter(it -> form.getId().equals(it.getId())).findFirst()
                    .orElseThrow(() -> new ObjectNotFoundException("Asset with id: " + form.getId() + " Not found!"));
        }
        if (form.getExternalId() != null) {
            // By hash, as the lookup matched: a raw string compare would miss a differently-cased id.
            Long hash = ExternalIds.hash(form.getExternalId());
            return assets.stream().filter(it -> hash.equals(it.getExternalIdHash())).findFirst()
                    .orElseThrow(() -> new ObjectNotFoundException(
                            "Asset with externalId: " + form.getExternalId() + " Not found!"));
        }
        throw new BadRequestException("Asset id or externalId is required.",
                new FieldErrors().addFieldError("id", "null").addFieldError("externalId", "null"));
    }

    /**
     * Delete assets by id or external id through the shared pipeline. Ids that are not assets are
     * left alone, the same way ids that do not exist are.
     */
    @Transactional
    public void delete(DataWrapper<IdCollection> apiReqData) throws PulsarClientException {
        if (apiReqData.getItems() == null || apiReqData.getItems().isEmpty()) return;

        var graph = new GraphDataWrapper<Resource, EdgeProxy>();
        assetRepository.findAllByIdCollection(apiReqData.getItems()).forEach(entity -> {
            Resource r = new Resource();
            r.setId(entity.getId());
            graph.getNodes().add(r);
        });
        if (graph.getNodes().isEmpty()) return;
        resourceService.delete(graph);
    }

    /**
     * Restrict the query to assets, replacing whatever the caller asked for.
     *
     * <p>Not merged with it: {@code nodeType} entries OR together, so leaving a caller-supplied
     * {@code ["timeseries"]} in place would widen a request to {@code /assets} into a mixed query.
     */
    private static void pinAssetType(ResourceFilter filter) {
        if (filter != null) {
            filter.setNodeType(List.of(TypeLabels.ASSET.toLowerCase()));
        }
    }

    private static DataWrapper<Asset> onlyAssets(Iterable<? extends NodeModel> nodes) {
        var out = new DataWrapper<Asset>();
        for (NodeModel node : nodes) {
            if (node instanceof Asset asset) {
                out.getItems().add(asset);
            }
        }
        return out;
    }
}
