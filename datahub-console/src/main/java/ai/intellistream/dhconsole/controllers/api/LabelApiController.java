// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.api.error.ConflictException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

@RestController
@RequestMapping(value = {"/api/label"})
@Slf4j
public class LabelApiController {

    private final DatahubApi datahubApi;
    private final MessageSource messageSource;
    private final JsonMapper jsonMapper;

    public LabelApiController(DatahubApi datahubApi, MessageSource messageSource, JsonMapper jsonMapper) {
        this.datahubApi = datahubApi;
        this.messageSource = messageSource;
        this.jsonMapper = jsonMapper;
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
        DataWrapper<LabelForm> labels = datahubApi.getLabelList();
        return new ResponseEntity<>(labels, HttpStatus.OK);
    }

    @RequestMapping(
            value = {"/save"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> save(@RequestBody @Valid LabelForm form, Locale locale) {
        DataWrapper<LabelForm> data = new DataWrapper<>();
        data.getItems().add(form);
        try{
            DataWrapper<LabelForm> labels = datahubApi.createLabel(data);
            var label = labels.getItems().stream().findFirst();
            return label.<ResponseEntity<?>>map(labelForm -> new ResponseEntity<>(labelForm, HttpStatus.OK))
                    .orElseGet(() -> new ResponseEntity<>(form, HttpStatus.BAD_REQUEST));
        } catch (ConflictException e){
            Map<String, Object> errorObject = (Map<String, Object>)jsonMapper.readValue(e.getMessage(), Map.class);
            Map<String, Object> error = (Map<String, Object>)errorObject.get("error");
            if(error.get("message").equals("Label with name already exists.")){
                var duplicatedLabels = ((List<String>)error.get("duplicated")).toArray();
                var errorMessage = messageSource.getMessage("label.name.already.exists", duplicatedLabels, locale);
                error.put("message", errorMessage);
                jsonMapper.writeValueAsString(errorMessage);
                return new ResponseEntity<>(errorObject, HttpStatus.CONFLICT);
            }

            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT);
        }
    }

    @RequestMapping(
            value = {"/update"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> update(@RequestBody @Valid LabelForm form){
        if(form.getId() == null){
            return new ResponseEntity<>("Missing label id...", HttpStatus.BAD_REQUEST);
        }
        DataWrapper<LabelForm> data = new DataWrapper<>();
        data.getItems().add(form);
        DataWrapper<LabelForm> labels = datahubApi.updateLabel(data);
        var label = labels.getItems().stream().findFirst();
        return label.<ResponseEntity<?>>map(labelForm -> new ResponseEntity<>(labelForm, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(form, HttpStatus.BAD_REQUEST));
    }

}
