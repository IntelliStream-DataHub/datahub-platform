// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.function.UpdateFunctionForm;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.FunctionRetreiver;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.datafilters.FunctionFilter;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.util.List;

/**
 * Functions — the {@code FUNCTION}-labelled corner of the resource graph, typed as
 * {@link Function}. Same relationship to {@link ResourceService} as {@link AssetService} has.
 */
public final class FunctionService {

    private final ApiHttp http;
    private final JavaType functions;  // DataWrapper<Function>
    private final JavaType nodeGraph;  // GraphDataWrapper<NodeModel, EdgeProxy> — the update echo

    public FunctionService(ApiHttp http) {
        this.http = http;
        TypeFactory tf = http.typeFactory();
        this.functions = tf.constructParametricType(DataWrapper.class, Function.class);
        this.nodeGraph = tf.constructParametricType(GraphDataWrapper.class, NodeModel.class, EdgeProxy.class);
    }

    /** POST /functions/create */
    public DataWrapper<Function> create(List<Function> items) {
        return http.post("/functions/create", new DataWrapper<Function>().setItems(items), functions);
    }

    /** GET /functions — the first {@code limit} functions, newest created first. */
    public DataWrapper<Function> list(int limit) {
        return http.get("/functions?limit=" + limit, functions);
    }

    /** GET /functions/{id} — one function by its numeric id; {@code 404} when there is none. */
    public DataWrapper<Function> getById(long id) {
        return http.get("/functions/" + id, functions);
    }

    /** POST /functions/byids — a batch by id or external id. Ids that match nothing are omitted. */
    public DataWrapper<Function> byIds(List<IdCollection> ids) {
        return http.post("/functions/byids", new DataWrapper<IdCollection>().setItems(ids), functions);
    }

    /** POST /functions/filter — the structured query over the criteria every node type shares. */
    public DataWrapper<Function> filter(FunctionRetreiver retriever) {
        return http.post("/functions/filter", retriever, functions);
    }

    /** {@link #filter(FunctionRetreiver)} with just the criteria and the default limit. */
    public DataWrapper<Function> filter(FunctionFilter criteria) {
        FunctionRetreiver retriever = new FunctionRetreiver();
        retriever.setFilter(criteria);
        return filter(retriever);
    }

    /** POST /functions/search — free-text search, optionally narrowed by the same filter. */
    public DataWrapper<Function> search(SearchBody<FunctionFilter> search) {
        return http.post("/functions/search", search, functions);
    }

    /** POST /functions/update — only the fields named in each entry's {@code update} block change. */
    public GraphDataWrapper<NodeModel, EdgeProxy> update(List<UpdateFunctionForm> nodes,
                                                        List<UpdateRelForm> relations) {
        GraphDataWrapper<UpdateFunctionForm, UpdateRelForm> request = new GraphDataWrapper<>();
        request.setNodes(nodes);
        request.setRelations(relations);
        return http.post("/functions/update", request, nodeGraph);
    }

    /** {@link #update(List, List)} for the common node-only update. */
    public GraphDataWrapper<NodeModel, EdgeProxy> update(List<UpdateFunctionForm> nodes) {
        return update(nodes, List.of());
    }

    /** DELETE /functions/delete — the endpoint answers {@code 204} with no body. */
    public void delete(List<IdCollection> ids) {
        http.send("DELETE", "/functions/delete", new DataWrapper<IdCollection>().setItems(ids));
    }
}
