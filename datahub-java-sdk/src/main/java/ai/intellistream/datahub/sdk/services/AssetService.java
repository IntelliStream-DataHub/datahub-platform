// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.util.List;

/**
 * Assets — the {@code ASSET}-labelled corner of the resource graph, typed as {@link Asset} rather
 * than the polymorphic {@link NodeModel} the {@code /resources} endpoints return.
 *
 * <p>An asset is a resource: {@link ResourceService} can read and write the same nodes, and does so
 * when you want one call to span several type-labels. Prefer these when the answer is only ever
 * assets, because the reads come back already typed and carry the asset fields without a cast.
 */
public final class AssetService {

    private final ApiHttp http;
    private final JavaType assets;     // DataWrapper<Asset>
    private final JavaType nodeGraph;  // GraphDataWrapper<NodeModel, EdgeProxy> — the update echo

    public AssetService(ApiHttp http) {
        this.http = http;
        TypeFactory tf = http.typeFactory();
        this.assets = tf.constructParametricType(DataWrapper.class, Asset.class);
        this.nodeGraph = tf.constructParametricType(GraphDataWrapper.class, NodeModel.class, EdgeProxy.class);
    }

    /** POST /assets/create */
    public DataWrapper<Asset> create(List<Asset> items) {
        return http.post("/assets/create", new DataWrapper<Asset>().setItems(items), assets);
    }

    /** GET /assets/{id} — one asset by its numeric id; {@code 404} when there is none. */
    public DataWrapper<Asset> getById(long id) {
        return http.get("/assets/" + id, assets);
    }

    /** POST /assets/byids — a batch by id or external id. Ids that match nothing are omitted. */
    public DataWrapper<Asset> byIds(List<IdCollection> ids) {
        return http.post("/assets/byids", new DataWrapper<IdCollection>().setItems(ids), assets);
    }

    /** GET /assets — the first {@code limit} assets, newest created first, with no criteria. */
    public DataWrapper<Asset> list(int limit) {
        return http.get("/assets?limit=" + limit, assets);
    }

    /**
     * POST /assets/filter — the structured query, on the same {@link ResourceFilter} as
     * {@link ResourceService#filter(ResourceFilter)}, narrowed to assets.
     */
    public DataWrapper<Asset> filter(ResourceRetreiver retriever) {
        return http.post("/assets/filter", retriever, assets);
    }

    /** {@link #filter(ResourceRetreiver)} with just the criteria and the default limit. */
    public DataWrapper<Asset> filter(ResourceFilter criteria) {
        ResourceRetreiver retriever = new ResourceRetreiver();
        retriever.setFilter(criteria);
        return filter(retriever);
    }

    /** POST /assets/search — free-text search, optionally narrowed by the same filter. */
    public DataWrapper<Asset> search(SearchBody<ResourceFilter> search) {
        return http.post("/assets/search", search, assets);
    }

    /**
     * POST /assets/update — change fields on assets and their relations. Only the fields named in
     * each entry's {@code update} block change.
     *
     * <p>The echo is the shared node graph, so nodes come back as {@link NodeModel}: an update may
     * touch relations whose other end is not an asset.
     */
    public GraphDataWrapper<NodeModel, EdgeProxy> update(List<UpdateResourceForm> nodes,
                                                        List<UpdateRelForm> relations) {
        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> request = new GraphDataWrapper<>();
        request.setNodes(nodes);
        request.setRelations(relations);
        return http.post("/assets/update", request, nodeGraph);
    }

    /** {@link #update(List, List)} for the common node-only update. */
    public GraphDataWrapper<NodeModel, EdgeProxy> update(List<UpdateResourceForm> nodes) {
        return update(nodes, List.of());
    }

    /** DELETE /assets/delete — the endpoint answers {@code 204} with no body. */
    public void delete(List<IdCollection> ids) {
        http.send("DELETE", "/assets/delete", new DataWrapper<IdCollection>().setItems(ids));
    }
}
