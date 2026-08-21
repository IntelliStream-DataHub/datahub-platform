// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.services.UnitService;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.transformers.UnitEntityTransformer;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/units")
@Slf4j
@AllArgsConstructor
@Tag(name = "Units", description = """
        Units are the standard list of measurement units available across DataHub (meters,
        celsius, litres per second, etc.). When you create a timeseries tied to a physical
        quantity, you pick one of these units by `externalId`. The list is managed centrally —
        this API is read-only.""")
public class UnitController {

    private final UnitService unitService;

    @Tag(name = "Units")
    @Operation(summary = "List all units",
            description = "Return every unit available to your tenant. The list is bounded (typical size ~100s) and fully cacheable."
    )
    @ApiResponse(responseCode = "200", description = "Returns a list of units", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = UnitModel.class)
    ))
    @RequestMapping(value = {""}, method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> list(){
        try{
            DataWrapper<UnitModel> data = new DataWrapper<>();
            var units = UnitEntityTransformer.toUnit( unitService.list(100000) );
            data.setItems(units);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (Exception e){
            log.error(e.getMessage(), e);
        }
        return new ResponseEntity<>("Internal programming error.", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Hidden
    @RequestMapping(value = {"/"}, method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> listWithSlash(){
        return list();
    }

    @Tag(name = "Units")
    @Operation(summary = "Find a unit by externalId",
            description = "Look up a single unit by its `externalId` (e.g. `celsius`, `kilogram`). Returns 404 if no unit has this externalId."
    )
    @ApiResponse(responseCode = "200", description = "The unit if it was found.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = UnitModel.class)
    ))
    @ApiResponse(responseCode = "404", description = "No unit with this externalId exists.")
    @RequestMapping(value = {"/{externalId}"}, method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> findByExternalId(@Parameter(description = "External id of the unit.", example = "temperature:deg_c") @PathVariable String externalId){
        var unit = unitService.findByExternalId(externalId);
        if (unit == null) {
            throw new ObjectNotFoundException("Unit with externalId: " + externalId + " not found");
        }
        DataWrapper<UnitModel> data = new DataWrapper<>();
        data.getItems().add(UnitEntityTransformer.toUnit(unit));
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @Tag(name = "Units")
    @Operation(summary = "Find units by id or externalId",
            description = "Look up several units in one call. Each entry in `items[]` needs either `id` or `externalId`. Missing ones are silently omitted."
    )
    @ApiResponse(responseCode = "200", description = "Returns a list of units. The list is empty if not unit is found.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = UnitModel.class)
    ))
    @RequestMapping(value = {"/byids"}, method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> byids(@RequestBody
                                       @Schema(implementation = IdCollectionDataWrapper.class)
                                       DataWrapper<IdCollection> form
    ){
        try{
            DataWrapper<UnitModel> data = new DataWrapper<>();
            Set<Long> idSet = form.getItems().stream().map(IdCollection::getId).filter(Objects::nonNull).collect(Collectors.toSet());
            Set<Long> externalIdHashSet = form.getItems().stream()
                    .filter(it -> it.getExternalId() != null)
                    .map( it -> IdGenerator.xxHash(it.getExternalId()))
                    .collect(Collectors.toSet());
            var units = unitService.findByIdAndExternalId(idSet, externalIdHashSet);
            data.setItems(units);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (Exception e){
            log.error(e.getMessage(), e);
        }
        return new ResponseEntity<>("Internal programming error.", HttpStatus.INTERNAL_SERVER_ERROR);
    }

}
