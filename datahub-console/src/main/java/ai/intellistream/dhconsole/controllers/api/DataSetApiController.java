// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.*;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.api.error.ConflictException;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * What is left of the console's dataset proxy: the reads a few pages still relay through this app,
 * plus the delete the tutorial sandbox uses.
 *
 * <p>Creating, updating, deleting and loading a dataset from the right-hand form no longer come
 * through here at all: that form calls datahub-api from the browser with the user's own bearer
 * token (see {@code static/js/right-form-content/datasets/form.js}), which is where new work goes,
 * since {@link DatahubApi} is deprecated. Relaying a write only hid the api's own error responses,
 * and an access denial came back as a body no page knew how to read.
 */
@RestController
@RequestMapping(value = {"/api/datasets"})
@Slf4j
public class DataSetApiController {

    private final DatahubApi datahubApi;

    public DataSetApiController(DatahubApi datahubApi) {
        this.datahubApi = datahubApi;
    }

    @RequestMapping(value = {"/list"}, method = RequestMethod.GET)
    public ResponseEntity<?> list(){
        DataSetRetreiver retreiver = new DataSetRetreiver();
        retreiver.setLimit(100);
        DataWrapper<DataSetModel> labels = datahubApi.listDataSets(retreiver);
        return new ResponseEntity<>(labels, HttpStatus.OK);
    }

    @RequestMapping(value = {"/search"}, method = RequestMethod.POST)
    public ResponseEntity<?> search(@RequestBody @Valid SearchBody<DataSetFilter> form){
        try{
            DataWrapper<DataSetModel> dataSets = datahubApi.searchDataSets(form);
            return new ResponseEntity<>(dataSets, HttpStatus.OK);
        } catch (Exception e){
            log.error(e.getMessage());
        }
        DataWrapper<DataSetModel> dataSets = new DataWrapper<>();
        return new ResponseEntity<>(dataSets, HttpStatus.OK);
    }

    @RequestMapping(value = {"/delete/{id}"}, method = {RequestMethod.POST, RequestMethod.DELETE})
    public ResponseEntity<?> delete(@PathVariable long id){
        DataWrapper<IdCollection> apiReqData = new DataWrapper<>();
        apiReqData.getItems().add( IdCollection.createFromId(id));
        try{
            datahubApi.deleteDataSets(apiReqData);
        } catch (ConflictException e){
            log.error(e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.valueOf(e.getErrorCode()));
        } catch (Exception e){
            log.error(e.getMessage());
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.noContent().build();
    }
}
