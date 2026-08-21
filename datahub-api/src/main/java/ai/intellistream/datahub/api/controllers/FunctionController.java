// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.*;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.FunctionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.services.FunctionService;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * A Function is a plain datastore node distinguished by its {@code FUNCTION} type-label.
 * It supports the full create/list/update/delete surface a resource does; every write is
 * delegated to the shared resource pipeline via {@link FunctionService}.
 */
@RestController
@RequestMapping("/functions")
@Slf4j
public class FunctionController {

    private final FunctionService functionService;

    public FunctionController(FunctionService functionService) {
        this.functionService = functionService;
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Create function",
            description = "Create one or more functions. A function is a plain datastore node "
                    + "with the same shape as a resource; each needs a unique externalId and a name."
    )
    @ApiResponse(responseCode = "201", description = "Function(s) created.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FunctionDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Bad request.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @PostMapping(
            path = "/create",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> createFunction(
            @Schema(implementation = FunctionDataWrapper.class)
            @RequestBody DataWrapper<Function> apiReqData) {
        try {
            DataWrapper<Function> data = functionService.create(apiReqData);
            return new ResponseEntity<>(data, HttpStatus.CREATED);
        } catch (ConstraintViolationException cve) {
            log.warn("Function create validation failed: {}", cve.getMessage());
            return new ResponseEntity<>(
                    BuildErrorResponse.createConstraintViolationError(cve), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException dve) {
            var e = BuildErrorResponse.createDataIntegrityViolationError(dve);
            return new ResponseEntity<>(e, HttpStatus.CONFLICT);
        } catch (DuplicateDataException e) {
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (BadRequestException e) {
            var error = e.getError();
            return new ResponseEntity<>(error, HttpStatusCode.valueOf(error.getError().getCode()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (PulsarClientException | RuntimeException e) {
            log.error("Function create failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Functions")
    @Operation(summary = "List functions", description = "List all functions for the current tenant.")
    @ApiResponse(responseCode = "200", description = "Functions returned.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FunctionDataWrapper.class)
            ))
    @GetMapping(path = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listFunctions() {
        try {
            return ResponseEntity.ok(functionService.list());
        } catch (RuntimeException e) {
            log.error("Function list failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Update function",
            description = "Update one or more functions (and any relations). Only the fields named in "
                    + "each entry's `update` block are changed."
    )
    @ApiResponse(responseCode = "200", description = "Function(s) updated.")
    @ApiResponse(responseCode = "400", description = "Bad request.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @PostMapping(
            path = "/update",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> updateFunction(
            @RequestBody GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData) {
        try {
            GraphDataWrapper<Resource, EdgeProxy> results = functionService.update(apiReqData);
            return new ResponseEntity<>(results, HttpStatus.OK);
        } catch (ConstraintViolationException cve) {
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (PulsarClientException | RuntimeException e) {
            log.error("Function update failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Delete function",
            description = "Delete one or more functions by id or externalId. Deleting a function removes "
                    + "all of its relationships; the delete is rejected if it would strand a surviving node."
    )
    @ApiResponse(responseCode = "204", description = "Function(s) deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "400", description = "Bad request.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @RequestMapping(
            path = "/delete",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            method = { RequestMethod.DELETE, RequestMethod.POST }
    )
    public ResponseEntity<?> deleteFunction(
            @Schema(implementation = IdCollectionDataWrapper.class)
            @RequestBody DataWrapper<IdCollection> apiReqData) {
        try {
            functionService.delete(apiReqData);
            return ResponseEntity.noContent().build();
        } catch (ResourceDeleteException e) {
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            log.warn("Function delete bad request: {}", e.getError().getError().getMessage());
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (PulsarClientException | RuntimeException e) {
            log.error("Function delete failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
