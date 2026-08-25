// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.resource.ResourceForm;
import ai.intellistream.datahub.resource.ResourceWebForm;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.dhconsole.api.error.ConflictException;
import ai.intellistream.dhconsole.services.ResourceApiService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;


@RestController
@RequestMapping("/api/resources")
@Slf4j
public class ResourceApiController {

    private final DatahubApi datahubApi;
    private final ResourceApiService resourceApiService;
    private final JsonMapper jsonMapper;

    public ResourceApiController(DatahubApi datahubApi, ResourceApiService resourceApiService,
                                 JsonMapper jsonMapper) {
        this.datahubApi = datahubApi;
        this.resourceApiService = resourceApiService;
        this.jsonMapper = jsonMapper;
    }

    @RequestMapping(value = {"/{id}"},
            method = RequestMethod.GET,
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> get(@PathVariable Long id){
        var data = this.datahubApi.getResourceById(id);
        if(!data.getItems().isEmpty()){
            var resource = data.getItems().iterator().next();
            return new ResponseEntity<>(resource, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @RequestMapping(value = {"/fetch-related-nodes"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> fetchRelatedNodes(@RequestBody RelatedResourcesForm form){
        ResourceNetwork resourceNetwork = this.datahubApi.fetchRelatedResources(form);
        return new ResponseEntity<>(resourceNetwork, HttpStatus.OK);
    }

    @RequestMapping(value = {"/byids"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> byIds(@RequestBody DataWrapper<IdCollection> apiReqData){
        DataWrapper<ai.intellistream.datahub.models.NodeModel> resources = this.datahubApi.byIds(apiReqData);
        return new ResponseEntity<>(resources, HttpStatus.OK);
    }

    @RequestMapping(value = {"/save"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> save(@Valid @RequestBody ResourceWebForm form){
        try {
            return respond(this.resourceApiService.save(form), HttpStatus.CREATED);
        } catch (ConflictException e){
            return forwardApiError(e);
        } catch (Exception e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = {"/update"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> update(@Valid @RequestBody ResourceForm form){
        try {
            return respond(this.resourceApiService.update(form), HttpStatus.OK);
        } catch (ConflictException e){
            return forwardApiError(e);
        } catch (Exception e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The saved node, with the envelope's {@code warnings} alongside it when the write raised any.
     *
     * <p>The browser forms read a bare node here rather than a {@code {"items":[...]}} envelope, so
     * the warnings are merged in as one more property instead of changing that shape. Absent when
     * empty, exactly as on the API's own responses, so a clean write is byte-identical to before.
     */
    private ResponseEntity<?> respond(GraphDataWrapper<Resource, EdgeProxy> result, HttpStatus status){
        Resource node = result.getNodes().stream().findFirst().orElse(null);
        if(node == null){
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
        ObjectNode body = jsonMapper.valueToTree(node);
        if(result.getWarnings() != null && !result.getWarnings().isEmpty()){
            body.set("warnings", jsonMapper.valueToTree(result.getWarnings()));
        }
        return new ResponseEntity<>(body, status);
    }

    /**
     * Forwards the API's error body and status verbatim.
     *
     * <p>Collapsing it into an empty 400 (which is what used to happen) would throw away the one
     * thing a naming-policy rejection is for: it names every offending external id and states that
     * nothing was created. Matches how the data set controller already handles this.
     */
    private ResponseEntity<?> forwardApiError(ConflictException e){
        log.error(e.getMessage());
        HttpStatusCode status = e.getErrorCode() != null
                ? HttpStatusCode.valueOf(e.getErrorCode())
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(e.getErrorMessage());
    }

    @RequestMapping(value = {"/search"},
            method = RequestMethod.POST,
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> search(@Valid @RequestBody SearchForm form){
        try {
            SearchBody<ResourceFilter> rs = new SearchBody<>();
            rs.setSearch(form);
            // Say which node types this picker wants, rather than taking whatever the endpoint
            // defaults to. POST /resources/search is the generic node search: it spans every type,
            // and it started returning policies when it stopped being five per-type queries that
            // happened never to ask the policy repository. A policy is not something to pick as a
            // related resource, so the omission is worth stating on purpose now that it can be.
            ResourceFilter pickable = new ResourceFilter();
            pickable.setNodeType(List.of("asset", "timeseries", "function", "resource", "dataset"));
            rs.setFilter(pickable);
            DataWrapper<ai.intellistream.datahub.models.NodeModel> resources = this.datahubApi.searchResource(rs);
            return new ResponseEntity<>(resources, HttpStatus.OK);
        } catch (Exception e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = {"/delete/{id}"},
            method = {RequestMethod.POST, RequestMethod.DELETE}
    )
    public ResponseEntity<?> delete(@PathVariable Long id){
        try {
            // The API's DELETE /resources/delete expects a DataWrapper<IdCollection> ({"items":[...]}),
            // not a GraphDataWrapper ({"nodes":[...]}). Sending the wrong shape deserializes to an
            // empty items list server-side, so nothing is deleted yet a 204 is returned.
            DataWrapper<IdCollection> apiData = new DataWrapper<>();
            apiData.getItems().add(IdCollection.createFromId(id));
            GraphDataWrapper<Resource, EdgeProxy> responseData = this.datahubApi.deleteResource(apiData);
            return new ResponseEntity<>(responseData, HttpStatus.NO_CONTENT);
        } catch (Exception e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

}
