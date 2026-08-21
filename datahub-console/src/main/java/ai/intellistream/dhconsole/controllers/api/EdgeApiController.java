// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.forms.RelFormWithId;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.services.ResourceApiService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/edges")
@Slf4j
public class EdgeApiController {

    private final ResourceApiService resourceApiService;

    private final DatahubApi datahubApi;

    public EdgeApiController(ResourceApiService resourceApiService, DatahubApi datahubApi) {
        this.resourceApiService = resourceApiService;
        this.datahubApi = datahubApi;
    }

    @RequestMapping(value = {"/{id}"}, method = RequestMethod.GET)
    public ResponseEntity<?> get(@PathVariable long id){
        DataWrapper<IdCollection> data = new DataWrapper<>();
        var entry = new IdCollection();
        entry.setId(id);
        data.getItems().add(entry);
        var responseData = datahubApi.getEdgesAndRelatedNodes(data);
        return new ResponseEntity<>(responseData, HttpStatus.OK);
    }

    @RequestMapping(value = {"/save"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> save(@Valid @RequestBody RelForm form){
        try {
            EdgeProxy edge = this.resourceApiService.saveEdge(form);
            return new ResponseEntity<>(edge, HttpStatus.CREATED);
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
    public ResponseEntity<?> update(@Valid @RequestBody RelFormWithId form){
        try {
            GraphDataWrapper<Resource, EdgeProxy> data = this.resourceApiService.updateEdge(form);
            return new ResponseEntity<>(data, HttpStatus.CREATED);
        } catch (Exception e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = {"/delete/{id}"},
            method = {RequestMethod.POST, RequestMethod.DELETE}
    )
    public ResponseEntity<?> delete(@PathVariable("id") Long id){
        try {
            this.resourceApiService.deleteEdge(id);
            return new ResponseEntity<>("", HttpStatus.NO_CONTENT);
        } catch (Exception e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

}
