// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.api.error.ConflictException;
import ai.intellistream.dhconsole.models.ExternalIdAndHours;
import ai.intellistream.dhconsole.services.DatapointService;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = {"/api/timeseries"})
public class TimeseriesApiController {

    private final DatahubApi datahubApi;
    private final DatapointService datapointService;

    public TimeseriesApiController(DatahubApi datahubApi, DatapointService datapointService) {
        this.datahubApi = datahubApi;
        this.datapointService = datapointService;
    }

    @RequestMapping(value = {"/data/by-hours-ago"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> dataByHoursAgo(@RequestBody ExternalIdAndHours reqData){
        try{
            DataWrapper<DataCollection<?>> data = datapointService.getDatapointsByHoursAgo(reqData);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (ConflictException e){
            log.error(e.getMessage());
            return new ResponseEntity<>(e.getErrorMessage(), HttpStatus.valueOf(e.getErrorCode()));
        } catch (Exception e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = {"/save"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> save(@RequestBody @Valid Timeseries newTimeseries){
        DataWrapper<Timeseries> data = new DataWrapper<>();
        data.getItems().add(newTimeseries);
        DataWrapper<Timeseries> result = datahubApi.createTimeseries(data);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @RequestMapping(value = {"/update"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> update(@RequestBody @Valid DataWrapper<UpdateTimeseries> apiReqData){
        try{
            var result = datahubApi.updateTimeseries(apiReqData);
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (RuntimeException e){
            log.error(e.getMessage());
            return new ResponseEntity<>("", HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = {"/search"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> search(@RequestBody SearchBody<TimeseriesFilter> reqData){
        DataWrapper<Timeseries> data = datahubApi.searchTimeseries(reqData);
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @RequestMapping(value = {"/byids"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> byids(@RequestBody DataWrapper<IdCollection> reqData ){
        DataWrapper<Timeseries> data = datahubApi.findTimeseriesByIds(reqData);
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @RequestMapping(value = {"/delete/{id}"},
            method = {RequestMethod.POST, RequestMethod.DELETE}
    )
    public ResponseEntity<?> delete(@PathVariable Long id){
        DataWrapper<IdCollection> data = new DataWrapper<>();
        data.setItems(List.of(IdCollection.createFromId(id)));
        datahubApi.deleteTimeseries(data);
        return new ResponseEntity<>("", HttpStatus.NO_CONTENT);
    }
}
