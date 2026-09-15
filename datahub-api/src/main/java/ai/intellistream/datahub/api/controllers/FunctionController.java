// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.controllers.errors.*;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.FunctionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.services.FunctionService;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import io.swagger.v3.oas.annotations.media.ExampleObject;
import ai.intellistream.datahub.api.controllers.errors.schema.DeleteRefusedProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.ValidationProblem;

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
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ValidationProblem.class)
            ))
    @PostMapping(
            path = "/create",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> createFunction(
            @Schema(implementation = FunctionDataWrapper.class)
            @RequestBody DataWrapper<Function> apiReqData) throws PulsarClientException {
        DataWrapper<Function> data = functionService.create(apiReqData);
        return new ResponseEntity<>(data, HttpStatus.CREATED);
    
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "List functions",
            description = """
                    The first `limit` functions you may read, newest created first. No body, no
                    criteria — the cheap read for "what have I got", the shape every collection in
                    this API answers to.

                    `limit` defaults to 1000 and may not exceed 10 000. This moved from
                    `GET /functions/list`, which was the only collection spelling the listing that
                    way, and which returned every function in the tenant with no cap.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The first `limit` functions, newest first.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FunctionDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "`limit` is not a positive integer \u2264 10000.",
            content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)
            ))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listFunctions(
            @Parameter(description = "Maximum number of functions to return. A positive integer up to 10000.",
                    example = "1000")
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        ProblemDetail rejection = ListingLimit.rejection(limit);
        if (rejection != null) {
            return new ResponseEntity<>(rejection, HttpStatus.BAD_REQUEST);
        }
        return ResponseEntity.ok(functionService.list(ListingLimit.resolve(limit)));
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Fetch one function by id",
            description = """
                    Returns the function with this id.

                    A function you may not read is reported as missing rather than forbidden, so a
                    404 does not tell you whether the id exists.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The function.")
    @ApiResponse(responseCode = "404", description = "No such function, or not readable by this caller.")
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getFunction(@PathVariable("id") Long id) {
        return new ResponseEntity<>(functionService.get(id), HttpStatus.OK);
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
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ValidationProblem.class)
            ))
    @PostMapping(
            path = "/update",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> updateFunction(
            @RequestBody GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData) throws PulsarClientException {
        GraphDataWrapper<NodeModel, EdgeProxy> results = functionService.update(apiReqData);
        return new ResponseEntity<>(results, HttpStatus.OK);
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
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ValidationProblem.class)
            ))
    @ApiResponse(responseCode = "409", description =
            """
            The delete conflicts with the current state. Nothing was removed, and the same request \
            will succeed once the conflict is resolved — branch on `type`:

            - `.../errors/would-strand` — the delete would disconnect part of the graph from its \
              root. `blockedBy` names the resources that would be stranded, so you can include \
              them in the deletion or keep a connecting path.
            - `.../errors/optimistic-lock` — another request modified or deleted one of the \
              targets between read and write. Re-fetch the current state and retry.
            """,
            content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = DeleteRefusedProblem.class),
                    examples = @ExampleObject(value = """
                            {
                              "type": "https://intellistream.ai/errors/would-strand",
                              "title": "Delete refused",
                              "status": 409,
                              "detail": "Deleting this selection would disconnect resource(s) [klp_valve_v9] from the graph root. Include them in the deletion or keep a connecting path.",
                              "blockedBy": [ { "externalId": "klp_valve_v9" } ]
                            }
                            """)
            ))
    @RequestMapping(
            path = "/delete",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            method = { RequestMethod.DELETE, RequestMethod.POST }
    )
    public ResponseEntity<?> deleteFunction(
            @Schema(implementation = IdCollectionDataWrapper.class)
            @RequestBody DataWrapper<IdCollection> apiReqData) throws PulsarClientException {
        functionService.delete(apiReqData);
        return ResponseEntity.noContent().build();
    }
}
