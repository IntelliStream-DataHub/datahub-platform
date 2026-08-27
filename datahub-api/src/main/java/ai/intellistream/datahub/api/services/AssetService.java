// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
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
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class AssetService {

    private final ResourceService resourceService;

    public AssetService(ResourceService resourceService) {
        this.resourceService = resourceService;
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

    /** Free-text search, with the same type pinning as {@link #filter}. */
    @Transactional(readOnly = true)
    public DataWrapper<Asset> search(SearchBody<ResourceFilter> searchForm) {
        pinAssetType(searchForm.getFilter());
        DataWrapper<NodeModel> page = resourceService.search(searchForm);
        return onlyAssets(page.getItems()).setNextCursor(page.getNextCursor());
    }

    /**
     * Update assets (and any relations) through the shared pipeline. The intrinsic {@code ASSET}
     * type-label stays immutable there, so an update cannot turn an asset into something else.
     */
    @Transactional
    public GraphDataWrapper<NodeModel, EdgeProxy> update(
            GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData) throws PulsarClientException {
        return resourceService.update(apiReqData);
    }

    /** Delete assets by id or external id through the shared pipeline. */
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
