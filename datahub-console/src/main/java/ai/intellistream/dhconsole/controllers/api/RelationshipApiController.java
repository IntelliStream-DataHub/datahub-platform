// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.resource.RelTypeForm;
import ai.intellistream.dhconsole.api.DatahubApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Optional;

@RestController
@RequestMapping(value = {"/api/relationship"})
public class RelationshipApiController {

    private final DatahubApi datahubApi;

    public RelationshipApiController(DatahubApi datahubApi) {
        this.datahubApi = datahubApi;
    }

    @RequestMapping(value = {"/{id}"}, method = RequestMethod.GET)
    public ResponseEntity<?> get(@PathVariable long id){
        DataWrapper<LabelForm> labels = datahubApi.getLabel(id);
        Optional<LabelForm> l = labels.getItems().stream().findFirst();
        if(l.isPresent()){
            return new ResponseEntity<>(l, HttpStatus.OK);
        }
        return new ResponseEntity<>(new ArrayList<>(), HttpStatus.OK);
    }

    @RequestMapping(value = {"/list"}, method = RequestMethod.GET)
    public ResponseEntity<?> list(){
        DataWrapper<Object> labels = datahubApi.getRelationshipTypeList();
        return new ResponseEntity<>(labels.getItems(), HttpStatus.OK);
    }

    @RequestMapping(
            value = {"/save"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> save(@RequestBody @Valid RelTypeForm form){
        DataWrapper<RelTypeForm> data = new DataWrapper<>();
        data.getItems().add(form);
        DataWrapper<RelTypeForm> responseData = datahubApi.createRelationshipType(data);
        var relType = responseData.getItems().stream().findFirst();
        return relType.<ResponseEntity<?>>map(rt -> new ResponseEntity<>(rt, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(form, HttpStatus.BAD_REQUEST));
    }

}
