// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.LabelDataWrapper;
import ai.intellistream.datahub.errors.EntityInUseException;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import ai.intellistream.datahub.services.LabelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/labels")
@Slf4j
@Tag(name = "Labels", description = """
        Labels categorise resources and timeseries. Every resource must carry at least one
        label (e.g. `PIPE`, `SENSOR`, `DOCUMENT`) so filters and the graph UI can group
        related objects together. Labels are tenant-scoped and shared across resources —
        creating a resource with a new label name auto-creates the label.""")
public class LabelController {

    private final LabelService labelService;
    private final LabelRepository labelRepository;

    public LabelController(
            LabelService labelService,
            LabelRepository labelRepository
    ) {
        this.labelService = labelService;
        this.labelRepository = labelRepository;
    }

    @Tag(name = "Labels")
    @Operation(summary = "Get label by id",
            description = "Look up a single label by its numeric `id`. Returns 404 if no label has this id."
    )
    @ApiResponse(responseCode = "200", description = "A single label object is returned.",
            content = @Content(
                    schema = @Schema(implementation = LabelDataWrapper.class)
            )
    )
    @ApiResponse(responseCode = "404", description = "No label with this id exists.")
    @RequestMapping(value = {"/{id}"},
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> get(@Parameter(description = "Numeric id of the label.", example = "5677892") @PathVariable Long id){
        Label label = labelRepository.findById(id).orElseThrow(() ->
                new ObjectNotFoundException("Label with id: " + id + " not found"));
        DataWrapper<Label> data = new DataWrapper<>();
        data.getItems().add(label);
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @Tag(name = "Labels")
    @Operation(summary = "List all labels",
            description = "Return every label in your tenant. Labels are a small, slow-changing set so listing them all is cheap."
    )
    @ApiResponse(responseCode = "200", description = "A collection with label objects is returned.",
            content = @Content(
                    schema = @Schema(implementation = LabelDataWrapper.class)
            )
    )
    @RequestMapping(value = {""},
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> list(){
        Collection<Label> labels = labelService.list();
        DataWrapper<Label> data = new DataWrapper<>();
        data.setItems(labels);
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @Tag(name = "Labels")
    @Operation(summary = "Create labels",
            description = """
                    Create one or more labels up-front. Labels are usually auto-created the
                    first time they're referenced in `POST /resources/create`; this endpoint
                    is for admin flows that want to pre-seed label names, colors, or i18n
                    codes before they're used.

                    Each label needs a unique `name` within your tenant. Optional fields:
                    `description`, `color` (hex e.g. `#3A9F2E`), `i18nCode` for localized UI.
                    """
    )
    @ApiResponse(responseCode = "409", description = "A label with this name already exists.",
            content = @Content(schema = @Schema(implementation = DuplicateError.class)))
    @ApiResponse(responseCode = "200", description = "A collection with newly created label objects is returned.",
            content = @Content(
                    schema = @Schema(implementation = LabelDataWrapper.class)
            )
    )
    @RequestMapping(value = { "/create"},
            method = RequestMethod.POST,
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> create(@RequestBody @Valid @Schema(implementation = LabelDataWrapper.class) DataWrapper<LabelForm> form) {
        try {
            // Validate if label name already exists.
            Set<Long> hashSet = new HashSet<>();
            form.getItems().forEach(label -> hashSet.add(IdGenerator.xxHash(label.getName())));
            List<Label> existingEntries = labelRepository.findAllByHashList(hashSet);
            if(!existingEntries.isEmpty()){
                List<Map<String, String>> existingExternalIds = existingEntries.stream()
                        .map( it -> Map.of("name", it.getName()))
                        .toList();
                ResponseError<DuplicateError> responseError = new ResponseError<>();
                var duplicateError = new DuplicateError();
                duplicateError.setMessage("Label with name already exists.");
                duplicateError.setDuplicated(existingExternalIds);
                responseError.setError(duplicateError);
                throw new DuplicateDataException(responseError);
            }

            // If labels doesn't exist, create them
            Collection<Label> labels = labelService.createLabels(form);
            DataWrapper<Label> data = new DataWrapper<>();
            data.setItems(labels);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (ConstraintViolationException cve) {
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException cve) {
            var e = BuildErrorResponse.createDataIntegrityViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        }
        catch (BadRequestException e) {
            log.error(e.getMessage(), e);
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (DuplicateDataException e) {
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        }
    }

    @Tag(name = "Labels")
    @Operation(summary = "Update labels",
            description = "Change `description`, `color`, or `i18nCode` on existing labels. Identify each " +
                    "by `id` or by `name` (most callers use the name; the id is synthetic). To rename a " +
                    "label, identify it by `id`, since a name used to look it up can't also be the new name."
    )
    @ApiResponse(responseCode = "200", description = "A collection with updated label objects is returned.",
            content = @Content(
                    schema = @Schema(implementation = LabelDataWrapper.class)
            )
    )
    @RequestMapping(value = { "/update"},
            method = RequestMethod.POST,
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> update(@RequestBody @Schema(implementation = LabelDataWrapper.class) DataWrapper<LabelForm> form){
        // An update identifies each label by id or name (most callers use the name — the id is
        // synthetic). An item carrying neither is a client mistake — reject it as a 400 rather than
        // letting the lookup fall through. An unknown label becomes a 404 via ObjectNotFoundException
        // thrown from the service.
        if (form.getItems() == null || form.getItems().stream()
                .anyMatch(lf -> lf.getId() == null && (lf.getName() == null || lf.getName().isBlank()))) {
            var responseError = new ResponseError<BadRequestError>();
            var badRequest = new BadRequestError();
            badRequest.setMessage("Each label update must identify the label by id or name.");
            responseError.setError(badRequest);
            return new ResponseEntity<>(responseError, HttpStatus.BAD_REQUEST);
        }
        Collection<Label> labels = labelService.updateLabels(form);
        DataWrapper<Label> data = new DataWrapper<>();
        data.setItems(labels);
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    /**
     * This will delete labels from the database.
     */
    @Tag(name = "Labels")
    @Operation(summary = "Delete labels",
            description = """
                    Delete one or more labels by `id`. The delete is rejected if any resource
                    is still using the label — remove the label from those resources first
                    (via `POST /resources/update` with `labels.remove`).
                    """
    )
    @ApiResponse(responseCode = "204", description = "A collection with updated label objects is returned.",
            content = @Content(
                    schema = @Schema(implementation = LabelDataWrapper.class)
            )
    )
    @RequestMapping(value = { "/delete"},
            method = {RequestMethod.POST, RequestMethod.DELETE},
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> delete(@RequestBody @Schema(implementation = IdCollectionDataWrapper.class) DataWrapper<IdCollection> form){
        try{
            labelService.delete(form);
        } catch (EntityInUseException e) {
            var error = new BadRequestError();
            error.setMessage(e.getMessage());
            List<Map<String, String>> fields = new ArrayList<>();
            for (EntityInUseException.Blocked b : e.getBlocked()) {
                for (Map<String, String> usage : b.getUsages()) {
                    var entry = new LinkedHashMap<String, String>();
                    entry.put(b.getEntityType().toLowerCase(), b.getEntityName());
                    entry.putAll(usage);
                    fields.add(entry);
                }
            }
            error.setFields(fields);
            ResponseError<BadRequestError> body = new ResponseError<>();
            body.setError(error);
            return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
        }
        // Bodyless 204. An empty-string body with produces=application/json gets written by Spring
        // and surfaces as 200, which is why this endpoint previously returned 200 instead of 204.
        return ResponseEntity.noContent().build();
    }
}
