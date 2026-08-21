// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.api;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.resource.RelTypeForm;
import ai.intellistream.datahub.resource.ResourceForm;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.dhconsole.models.TimeseriesQueryParams;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
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
    GraphDataWrapper<Resource, EdgeProxy> createResourcesAndRelations(GraphDataWrapper<ResourceForm, RelForm> apiReqData);

    @RequestLine("GET /resources/{id}")
    DataWrapper<Resource> getResourceById(@Param("id") Long id);

    @RequestLine("POST /resources/byids")
    DataWrapper<Resource> byIds(DataWrapper<IdCollection> apiReqData);

    @RequestLine("POST /resources/fetch-related")
    ResourceNetwork fetchRelatedResources(RelatedResourcesForm apiReqData);

    @RequestLine("POST /resources/update")
    GraphDataWrapper<Resource, EdgeProxy> updateResourcesAndRelations(GraphDataWrapper<UpdateResourceForm, UpdateRelForm> form);

    @RequestLine("POST /resources/filter")
    DataWrapper<Resource> filter(ResourceRetreiver apiReqData);

    @RequestLine("GET /resources/{id}")
    DataWrapper<Resource> get(@Param("id") Long id);

    @RequestLine("DELETE /resources/delete")
    GraphDataWrapper<Resource, EdgeProxy> deleteResource(DataWrapper<IdCollection> apiReqData);

    @RequestLine("POST /resources/search")
    DataWrapper<Resource> searchResource(SearchBody<ResourceFilter> form);

    @RequestLine("GET /edges/{id}")
    DataWrapper<EdgeProxy> getEdgeById(@Param("id") Long id);

    @RequestLine("POST /edges/byids")
    GraphDataWrapper<Resource, EdgeProxy> getEdgeWithNodesById(DataWrapper<IdCollection> apiReqData);

    @RequestLine("POST /timeseries/data/list")
    DataWrapper<DataCollection<?>> retrieveDatapoints(DataRetriever<RetrieveFilter> apiReqData);

    @RequestLine("GET /labels")
    DataWrapper<LabelForm> getLabelList();

    @RequestLine("GET /labels/{id}")
    DataWrapper<LabelForm> getLabel(@Param("id") Long id);

    @RequestLine("POST /labels/create")
    DataWrapper<LabelForm> createLabel(DataWrapper<LabelForm> data);

    @RequestLine("POST /labels/update")
    DataWrapper<LabelForm> updateLabel(DataWrapper<LabelForm> data);

    @RequestLine("GET /edges/types")
    DataWrapper<Object> getRelationshipTypeList();

    @RequestLine("POST /edges/types/create")
    DataWrapper<RelTypeForm> createRelationshipType(DataWrapper<RelTypeForm> data);

    @RequestLine("POST /edges/byids")
    GraphDataWrapper<Resource, EdgeProxy> getEdgesAndRelatedNodes(DataWrapper<IdCollection> apiReqData);

    @RequestLine("DELETE /edges/delete")
    void deleteEdges(DataWrapper<IdCollection> apiReqData);

    @RequestLine("POST /datasets/list")
    DataWrapper<DataSetModel> listDataSets(DataSetRetreiver apiReqData);

    @RequestLine("POST /datasets/search")
    DataWrapper<DataSetModel> searchDataSets(SearchBody<DataSetFilter> apiReqData);

    // Creating, updating and loading a single dataset are gone from here: the console's dataset
    // form calls datahub-api from the browser instead.
    @RequestLine("POST /datasets/delete")
    DataWrapper<DataSetModel> deleteDataSets(DataWrapper<IdCollection> apiReqData);

    // POLICIES
    @RequestLine("GET /policies")
    DataWrapper<Policy> getPolicies();

    @RequestLine("GET /policies/types")
    DataWrapper<Policy> getPolicyTypes();

    @RequestLine("GET /policies/{policyNodeId}")
    DataWrapper<Policy> getPolicyById(@Param("policyNodeId") Long policyNodeId);

    @RequestLine("POST /policies/apply-template?policyNodeId={policyNodeId}&templateId={templateId}")
    DataWrapper<Policy> applyPolicyTemplate(
            @Param("policyNodeId") Long policyNodeId,
            @Param("templateId") Long templateId
    );

    @RequestLine("POST /policies/create")
    DataWrapper<Policy> createPolicies(DataWrapper<Policy> wrapper);

    @RequestLine("POST /policies/update")
    DataWrapper<Policy> updatePolicy(DataWrapper<UpdatePolicyForm> wrapper);

    @RequestLine("DELETE /policies/delete")
    void deletePolicies(DataWrapper<IdCollection> wrapper);

    @RequestLine("GET /governance/templates")
    DataWrapper<GovernanceTemplateDTO> getGovernanceTemplates();

    @RequestLine("GET /governance/templates/{templateId}")
    DataWrapper<GovernanceTemplateDTO> getGovernanceTemplateById(
            @Param("templateId") Long templateId
    );

    // TIMESERIES
    @RequestLine("GET /timeseries")
    DataWrapper<Timeseries> getTimeseriesList(@QueryMap TimeseriesQueryParams queryParams);

    @RequestLine("POST /timeseries/create")
    DataWrapper<Timeseries> createTimeseries(DataWrapper<Timeseries> data);

    @RequestLine("POST /timeseries/update")
    DataWrapper<Timeseries> updateTimeseries(DataWrapper<UpdateTimeseries> data);

    @RequestLine("POST /timeseries/search")
    DataWrapper<Timeseries> searchTimeseries(SearchBody<TimeseriesFilter> reqData);

    @RequestLine("POST /timeseries/byids")
    DataWrapper<Timeseries> findTimeseriesByIds(DataWrapper<IdCollection> apiReqData);

    @RequestLine("POST /timeseries/delete")
    void deleteTimeseries(DataWrapper<IdCollection> data);

    // UNITS
    @RequestLine("GET /units")
    DataWrapper<UnitModel> getUnits();

    @RequestLine("POST /units/byids")
    DataWrapper<UnitModel> findUnitsByIds(DataWrapper<UnitModel> apiReqData);

    // TENANT
    @RequestLine("GET /tenant/features")
    TenantFeatures getTenantFeatures();

    // FILES
    @RequestLine("GET /files/list{path}")
    DataWrapper<IndexNode> listDirectory(@Param("path") String path);

    @RequestLine("GET /files/download/{id}")
    ResponseEntity<org.springframework.core.io.Resource> download(@Param("id") String id);
}
