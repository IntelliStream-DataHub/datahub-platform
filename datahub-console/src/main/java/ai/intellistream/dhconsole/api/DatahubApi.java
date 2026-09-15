// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.dhconsole.models.TimeseriesQueryParams;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import feign.Headers;
import feign.Param;
import feign.QueryMap;
import feign.RequestLine;
import org.springframework.http.ResponseEntity;

/**
 * @deprecated Do not use for new functionality. The console should not proxy datahub-api through this
 * Feign client; new features call datahub-api directly from the browser (bearer token from
 * {@code GET /token}). The remaining code paths using this client will be cleaned up later.
 */
@Deprecated
@Headers({"Content-Type: application/json", "Accept-Encoding: gzip"})
public interface DatahubApi {

    @RequestLine("POST /resources/create")
    GraphDataWrapper<NodeModel, EdgeProxy> createResourcesAndRelations(GraphDataWrapper<NodeModel, RelForm> apiReqData);

    @RequestLine("GET /resources/{id}")
    DataWrapper<NodeModel> getResourceById(@Param("id") Long id);

    @RequestLine("POST /resources/byids")
    DataWrapper<NodeModel> byIds(DataWrapper<IdCollection> apiReqData);

    @RequestLine("POST /resources/fetch-related")
    ResourceNetwork fetchRelatedResources(RelatedResourcesForm apiReqData);

    @RequestLine("POST /resources/update")
    GraphDataWrapper<NodeModel, EdgeProxy> updateResourcesAndRelations(GraphDataWrapper<UpdateResourceForm, UpdateRelForm> form);

    @RequestLine("POST /resources/filter")
    DataWrapper<NodeModel> filter(ResourceRetreiver apiReqData);

    @RequestLine("DELETE /resources/delete")
    GraphDataWrapper<Resource, EdgeProxy> deleteResource(DataWrapper<IdCollection> apiReqData);

    @RequestLine("POST /resources/search")
    DataWrapper<NodeModel> searchResource(SearchBody<ResourceFilter> form);



    @RequestLine("POST /edges/byids")
    GraphDataWrapper<Resource, EdgeProxy> getEdgesAndRelatedNodes(DataWrapper<IdCollection> apiReqData);

    @RequestLine("DELETE /edges/delete")
    void deleteEdges(DataWrapper<IdCollection> apiReqData);

    // GET rather than the POST /datasets/list this used to call: that endpoint took a full
    // DataSetRetreiver body and called the same handler as POST /datasets/filter, and every caller
    // here filled the body with nothing but a limit. The api now spells the no-criteria listing the
    // way the rest of the collections do, so a limit is all this has to send.
    @RequestLine("GET /datasets?limit={limit}")
    DataWrapper<DataSetModel> listDataSets(@Param("limit") int limit);

    // POLICIES
    @RequestLine("GET /policies")
    DataWrapper<Policy> getPolicies();

    // TIMESERIES
    @RequestLine("GET /timeseries")
    DataWrapper<Timeseries> getTimeseriesList(@QueryMap TimeseriesQueryParams queryParams);

    // TENANT
    @RequestLine("GET /tenant/features")
    TenantFeatures getTenantFeatures();

    // FILES
    @RequestLine("GET /files/list{path}")
    DataWrapper<IndexNode> listDirectory(@Param("path") String path);

}
