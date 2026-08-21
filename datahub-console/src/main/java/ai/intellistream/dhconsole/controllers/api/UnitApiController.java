// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.dhconsole.api.DatahubApi;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping(value = {"/api/units"})
@Slf4j
public class UnitApiController {

    private final DatahubApi datahubApi;

    public UnitApiController(DatahubApi datahubApi) {
        this.datahubApi = datahubApi;
    }

    private static @NotNull ResponseEntity<DataWrapper<UnitModel>> sortAndRenderResponse(
            Locale locale,
            List<UnitModel> units,
            DataWrapper<UnitModel> wrapper
    ) {
        // Sort units using locale-sensitive comparison
        Comparator<UnitModel> comparator = (u1, u2) ->
                Collator.getInstance(locale).compare(u1.getName(), u2.getName());
        units.sort(comparator);

        wrapper.setItems(units);
        return new ResponseEntity<>(wrapper, HttpStatus.OK);
    }

    @RequestMapping(value = {""}, method = RequestMethod.GET)
    public ResponseEntity<?> list(Locale locale){
        DataWrapper<UnitModel> wrapper = datahubApi.getUnits();
        List<UnitModel> units = new ArrayList<>(wrapper.getItems());
        return sortAndRenderResponse(locale, units, wrapper);
    }

    @RequestMapping(
            value = {"/byids"},
            method = RequestMethod.POST,
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> byIds(@RequestBody  DataWrapper<UnitModel> dataRequest, Locale locale){
        DataWrapper<UnitModel> wrapper = datahubApi.findUnitsByIds(dataRequest);
        List<UnitModel> units = new ArrayList<>(wrapper.getItems());
        return sortAndRenderResponse(locale, units, wrapper);
    }
}
