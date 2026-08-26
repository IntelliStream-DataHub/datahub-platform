// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.FetchNearestResourcesForm;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.models.SearchBody;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.util.List;

/**
 * Resources — hierarchical asset-like entities and the relationships between them.
 * Mirrors the {@code /resources} endpoints.
 */
public final class ResourceService {

    private final ApiHttp http;
    private final JavaType nodes;            // DataWrapper<NodeModel> — typed reads
    private final JavaType nodeGraph;        // GraphDataWrapper<NodeModel, EdgeProxy> — typed create echo
    private final JavaType resourceGraph;    // GraphDataWrapper<Resource, EdgeProxy> — flat delete echo
    private final JavaType resourceNetwork;  // ResourceNetwork

    public ResourceService(ApiHttp http) {
        this.http = http;
        TypeFactory tf = http.typeFactory();
        this.nodes = tf.constructParametricType(DataWrapper.class, NodeModel.class);
        this.nodeGraph = tf.constructParametricType(GraphDataWrapper.class, NodeModel.class, EdgeProxy.class);
        this.resourceGraph = tf.constructParametricType(GraphDataWrapper.class, Resource.class, EdgeProxy.class);
        this.resourceNetwork = tf.constructType(ResourceNetwork.class);
    }

    /**
     * GET /resources/{id}. The node comes back typed by its type-label: a TIMESERIES-labelled
     * node is a {@code Timeseries}, a DATASET a {@code DataSetModel}, and so on; a node with no
     * type-label is a plain {@code Resource}.
     */
    public DataWrapper<NodeModel> getById(long id) {
        return http.get("/resources/" + id, nodes);
    }

    /** POST /resources/byids — fetch nodes by numeric id or external id, typed by type-label. */
    public DataWrapper<NodeModel> byIds(List<IdCollection> ids) {
        DataWrapper<IdCollection> request = new DataWrapper<IdCollection>().setItems(ids);
        return http.post("/resources/byids", request, nodes);
    }

    /**
     * POST /resources/filter — <b>the generic node query.</b> Unlike {@code /datasets/filter} and
     * {@code /timeseries/filter}, which each answer for one type, this one spans every node type:
     * assets, timeseries, functions, resources, data sets and policies share a table and the
     * criteria on the filter base, so one call searches across them. Narrow it with
     * {@code nodeType}, and read what came back off each node's type label.
     *
     * <p>Criteria AND together; within a list field the entries OR: {@code id},
     * {@code externalId}, {@code name}, {@code source}, {@code labels}, {@code metadata},
     * {@code createdTime}, {@code lastUpdatedTime}, plus {@code dataSetId}, {@code nodeType} and
     * {@code isRoot}. {@code externalId}, {@code name} and {@code source} take literals or
     * patterns in the same list ({@code *} and {@code %} are wildcards, {@code _} is literal,
     * case-insensitive); {@code labels} and {@code metadata} require all entries rather than any,
     * where a null metadata value asks for the key alone.
     *
     * <p>{@code dataSetId} expands down the {@code BELONGS_TO} hierarchy, and is the one list
     * where null and empty differ: omitted places no restriction, an explicit {@code []} matches
     * nothing.
     *
     * <p>The OR'd fields are named in the singular but still take lists — each accepts a bare value
     * or an array, so {@code name: "Pump 1"} and {@code name: ["Pump 1", "Pump 2"]} are both valid.
     * {@code labels} stays plural because its entries AND. These fields once existed as scalars
     * <em>alongside</em> plural list forms, and the two ANDed rather than merged; the scalars were
     * removed and the lists have since taken their names back, so a body naming one thing means
     * what it always did.
     *
     * <p>Results come newest created first unless the retriever carries a {@code sort}, capped by
     * its {@code limit} (default 1000, max 10000). Past that cap, page with the retriever's
     * {@code cursor} and the response's {@code nextCursor}.
     */
    public DataWrapper<NodeModel> filter(ResourceRetreiver retriever) {
        return http.post("/resources/filter", retriever, nodes);
    }

    /** {@link #filter(ResourceRetreiver)} with just the criteria and the default limit. */
    public DataWrapper<NodeModel> filter(ResourceFilter criteria) {
        ResourceRetreiver request = new ResourceRetreiver();
        request.setFilter(criteria);
        return filter(request);
    }

    /** POST /resources/search */
    public DataWrapper<NodeModel> search(SearchBody<ResourceFilter> search) {
        return http.post("/resources/search", search, nodes);
    }

    /**
     * POST /resources/create — create resources, optionally with relations between them.
     * Returns the created graph (nodes as {@link Resource}, relations as {@link EdgeProxy}).
     */
    /**
     * POST /resources/create. Any creatable node kind rides one call — an {@link Asset} with a
     * geoLocation, a {@code DataSetModel}, a {@code Timeseries} next to the assets it measures —
     * each dispatched by the type-label its DTO carries. The echo comes back typed.
     */
    public GraphDataWrapper<NodeModel, EdgeProxy> create(List<? extends NodeModel> nodes, List<RelForm> relations) {
        GraphDataWrapper<NodeModel, RelForm> request = new GraphDataWrapper<>();
        request.setNodes(new java.util.ArrayList<>(nodes));
        // Null relations is the common case for a node-only create.
        request.setRelations(relations == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(relations));
        return http.post("/resources/create", request, nodeGraph);
    }

    /** DELETE /resources/delete — delete resources by id; returns the removed graph. */
    public GraphDataWrapper<Resource, EdgeProxy> delete(List<IdCollection> ids) {
        DataWrapper<IdCollection> request = new DataWrapper<IdCollection>().setItems(ids);
        return http.delete("/resources/delete", request, resourceGraph);
    }

    /**
     * POST /resources/fetch-related — walk the graph outward from a starting resource and return
     * the connected sub-graph: the {@link ResourceNetwork} of nodes, the edges between them, and
     * their labels. Traversal is undirected and bounded by {@link RelatedResourcesForm#getDepth()}
     * (default {@code -1} = the whole connected component), optionally filtered to specific
     * relationship types and capped by {@code limit}.
     *
     * <p>Use it to reason about how things relate — e.g. whether two alarmed sensors share a
     * common subsystem — which a flat {@link #byIds(List)} read cannot answer.
     */
    public ResourceNetwork fetchRelated(RelatedResourcesForm form) {
        return http.post("/resources/fetch-related", form, resourceNetwork);
    }

    /**
     * Convenience over {@link #fetchRelated(RelatedResourcesForm)}: the sub-graph within
     * {@code depth} hops of the resource identified by {@code externalId}.
     */
    public ResourceNetwork fetchRelated(String externalId, int depth) {
        RelatedResourcesForm form = new RelatedResourcesForm();
        form.setExternalId(externalId);
        form.setDepth(depth);
        return fetchRelated(form);
    }

    /**
     * POST /resources/fetch-nearest — breadth-first from the starting resource, returning the closest
     * {@link FetchNearestResourcesForm#getLimit()} nodes carrying one of {@code endLabels} plus the
     * sub-graph connecting them. The cap is on matching END-nodes (e.g. the 10 nearest TIMESERIES),
     * not on hop depth or total node count like {@link #fetchRelated(RelatedResourcesForm)}.
     */
    public ResourceNetwork fetchNearest(FetchNearestResourcesForm form) {
        return http.post("/resources/fetch-nearest", form, resourceNetwork);
    }

    /**
     * POST /resources/update — change fields on existing resources and relations.
     *
     * <p>Only the fields named in each entry's {@code update} block change; the rest keep their
     * current value. Identify a node by {@code id} or {@code externalId}, a relation by {@code id}.
     * Pass an empty list for whichever half you are not touching.
     *
     * <p>The batch is all-or-nothing, and a concurrent write loses: if another request changed or
     * deleted one of these while yours was in flight, the call fails with {@code 409} and nothing is
     * written. Re-read with {@link #byIds(List)} and retry against fresh state — and prefer
     * {@code add} over {@code set} on collections, since two writers adding entries both survive a
     * retry where two writers setting them clobber each other.
     */
    public GraphDataWrapper<NodeModel, EdgeProxy> update(List<UpdateResourceForm> nodes,
                                                        List<UpdateRelForm> relations) {
        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> request = new GraphDataWrapper<>();
        request.setNodes(nodes);
        request.setRelations(relations);
        return http.post("/resources/update", request, nodeGraph);
    }

    /** Convenience over {@link #update(List, List)} for the common node-only update. */
    public GraphDataWrapper<NodeModel, EdgeProxy> update(List<UpdateResourceForm> nodes) {
        return update(nodes, List.of());
    }
}
