// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.RelationshipType;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.resource.RelTypeForm;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.util.List;

/**
 * Relationships (edges) between resources, and the relationship-type catalog.
 * Mirrors the {@code /edges} endpoints.
 *
 * <p>Edges link resources that already exist. To create the resources <em>and</em> their links in
 * one atomic call, use {@link ResourceService#create(List, List)} ({@code POST /resources/create})
 * instead.
 */
public final class EdgeService {

    private final ApiHttp http;
    private final JavaType edges;              // DataWrapper<EdgeProxy>
    private final JavaType edgeGraph;          // GraphDataWrapper<Resource, EdgeProxy>
    private final JavaType relationshipTypes;  // DataWrapper<RelationshipType>

    public EdgeService(ApiHttp http) {
        this.http = http;
        TypeFactory tf = http.typeFactory();
        this.edges = tf.constructParametricType(DataWrapper.class, EdgeProxy.class);
        this.edgeGraph = tf.constructParametricType(GraphDataWrapper.class, Resource.class, EdgeProxy.class);
        this.relationshipTypes = tf.constructParametricType(DataWrapper.class, RelationshipType.class);
    }

    /** GET /edges/{id} — look up a single relationship by its numeric id. */
    public DataWrapper<EdgeProxy> findById(long id) {
        return http.get("/edges/" + id, edges);
    }

    /**
     * POST /edges/byids — look up several relationships by id, each with the two resources it
     * connects (returned as {@code nodes}), saving a follow-up call to resolve the endpoints.
     */
    public GraphDataWrapper<Resource, EdgeProxy> byIds(List<IdCollection> ids) {
        DataWrapper<IdCollection> request = new DataWrapper<IdCollection>().setItems(ids);
        return http.post("/edges/byids", request, edgeGraph);
    }

    /**
     * POST /edges/create — link resources that already exist.
     *
     * <p>Each {@link RelForm} names its endpoints by numeric id or external id and the relationship
     * by type name (an unknown name is created on the fly). Relations are directional
     * ({@code from} → {@code to}), the batch is all-or-nothing, and you need write access to the
     * data sets of both endpoints.
     */
    public DataWrapper<EdgeProxy> create(List<RelForm> relations) {
        DataWrapper<RelForm> request = new DataWrapper<RelForm>().setItems(relations);
        return http.post("/edges/create", request, edges);
    }

    /** GET /edges/types — every relationship type defined for the tenant. */
    public DataWrapper<RelationshipType> types() {
        return http.get("/edges/types", relationshipTypes);
    }

    /**
     * POST /edges/types/create — register relationship type names up front. Types are otherwise
     * auto-created the first time a name is used; existing types are returned unchanged. Names are
     * case-insensitive and normalised to uppercase snake case ({@code Flows To} → {@code FLOWS_TO}).
     */
    public DataWrapper<RelationshipType> createTypes(List<RelTypeForm> types) {
        DataWrapper<RelTypeForm> request = new DataWrapper<RelTypeForm>().setItems(types);
        return http.post("/edges/types/create", request, relationshipTypes);
    }

    /**
     * DELETE /edges/delete — delete relationships by id; the resources at each end stay intact.
     * Idempotent: unknown ids are silently skipped, and the endpoint returns no body.
     */
    public void delete(List<IdCollection> ids) {
        DataWrapper<IdCollection> request = new DataWrapper<IdCollection>().setItems(ids);
        http.send("DELETE", "/edges/delete", request);
    }
}
