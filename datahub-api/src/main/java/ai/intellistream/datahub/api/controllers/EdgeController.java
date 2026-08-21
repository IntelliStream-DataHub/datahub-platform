// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.ConflictError;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.docs.RelationshipTypeDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.EdgeDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.RelFormDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.RelTypeFormDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.ResourceGraphDataWrapper;
import ai.intellistream.datahub.api.services.EdgeService;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.resource.RelTypeForm;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/edges")
@Slf4j
public class EdgeController {

    private final EdgeService edgeService;
    private final RelationshipTypeRepository relationshipTypeRepository;

    public EdgeController(EdgeService edgeService, RelationshipTypeRepository relationshipTypeRepository) {
        this.edgeService = edgeService;
        this.relationshipTypeRepository = relationshipTypeRepository;
    }

    @Tag(name = "Relationships")
    @Operation(
            summary = "Find relationship by id",
            description = "Look up a single relationship (edge) between two resources by its numeric `id`."
    )
    @ApiResponse(responseCode = "200", description = "The relationship was found.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = EdgeDataWrapper.class)
            ))
    @ApiResponse(responseCode = "404", description = "No relationship with this id exists.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(type = "string", example = "Could not find edge with id: 42")
            ))
    @RequestMapping(value = "/{id}", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> get(
            @Parameter(description = "Numeric id of the relationship to look up.", example = "5677892")
            @PathVariable("id") Long id){
        DataWrapper<EdgeProxy> items = edgeService.findById(id);
        if (items.getItems().isEmpty()) {
            throw new ObjectNotFoundException("Edge with id: " + id + " not found");
        }
        return new ResponseEntity<>(items, HttpStatus.OK);
    }

    @Tag(name = "Relationships")
    @Operation(
            summary = "Find relationships by id, with endpoints",
            description = """
                    Look up several relationships in one call. The response includes each
                    relationship and the two resources it connects (as `nodes[]`) — saves you
                    a follow-up call to resolve resource details.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Found relationships plus the resources they connect.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ResourceGraphDataWrapper.class)
            ))
    @ApiResponse(responseCode = "404", description = "None of the given ids match a relationship.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(type = "string", example = "Could not find edges for the given ids")
            ))
    @RequestMapping(value = "/byids", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> byIds(
            @Schema(implementation = IdCollectionDataWrapper.class)
            @RequestBody DataWrapper<IdCollection> apiReqData){
        try{
            GraphDataWrapper<Resource, EdgeProxy> items = edgeService.findByIdCollection(apiReqData.getItems());
            return new ResponseEntity<>(items, HttpStatus.OK);
        } catch (ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Relationships")
    @Operation(
            summary = "Create relationships",
            description = """
                    Link resources that already exist. Identify each endpoint by either its
                    numeric `fromId`/`toId` or its `fromExternalId`/`toExternalId`, and name the
                    relationship with `relationshipType` (or `relationshipTypeId`). An unknown
                    type name is created on the fly.

                    To create the resources **and** their links in one atomic call, use
                    `POST /resources/create` instead — this endpoint only connects resources
                    that are already there.

                    ### Rules
                    - A relation whose target is a **data set** must use `BELONGS_TO` — that is
                      what membership means, and any other type would look like structure while
                      meaning nothing to the hierarchy.
                    - A **time series** belongs to a single data set, so it cannot be connected
                      to a second one.
                    - You need write access to the data sets of **both** endpoints.

                    ### All-or-nothing
                    If any relation in the request fails, none of them are created.
                    """
    )
    @ApiResponse(responseCode = "201", description = "The relationships were created, with their server-assigned ids.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = EdgeDataWrapper.class),
                    examples = @ExampleObject(value = """
                            {
                              "items": [
                                {
                                  "id": "341",
                                  "start": 5677892,
                                  "end": 5677893,
                                  "type": "FLOWS_TO",
                                  "relationshipTypeId": "88"
                                }
                              ]
                            }
                            """)
            ))
    @ApiResponse(responseCode = "400", description =
            "The request has a problem the server could spot before saving anything — an endpoint " +
                    "that doesn't exist, a missing relationship type, or a relation the graph " +
                    "rules forbid.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = BadRequestError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 400,
                                "message": "Could not find fromNode",
                                "fields": [
                                  { "externalId": "klp_valve_v9", "id": "null" }
                                ]
                              }
                            }
                            """)
            ))
    @ApiResponse(responseCode = "403", description =
            "You lack write access to the data set of one of the endpoints.", content = @Content)
    @ApiResponse(responseCode = "409", description =
            "The same relationship already exists between these two resources.", content = @Content)
    @RequestMapping(value = "/create", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "The relationships to create.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = RelFormDataWrapper.class),
                            examples = @ExampleObject(
                                    name = "Pipe flows to valve",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "fromExternalId": "klp_pipe_ws_a1212_dl",
                                                  "toExternalId": "klp_valve_v9",
                                                  "relationshipType": "FLOWS_TO",
                                                  "description": "Downstream leg"
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody @Valid DataWrapper<RelForm> apiReqData){
        try {
            DataWrapper<EdgeProxy> created = edgeService.createRelationships(apiReqData);
            return new ResponseEntity<>(created, HttpStatus.CREATED);
        }
        // Bean constraints the service re-checks (MCP calls it directly), e.g. a relation with
        // neither a relationship type name nor an id.
        catch (ConstraintViolationException cve){
            return new ResponseEntity<>(BuildErrorResponse.createConstraintViolationError(cve), HttpStatus.BAD_REQUEST);
        }
        // The (start, end, relationship_type) unique constraint — the edge is already there.
        catch (DataIntegrityViolationException dve){
            log.warn("Rejected relationship creation: {}", dve.getMessage());
            return new ResponseEntity<>(BuildErrorResponse.createDataIntegrityViolationError(dve), HttpStatus.CONFLICT);
        }
        // Unresolvable endpoint or an edge-rule violation; the error carries its own status.
        catch (BadRequestException e){
            var error = e.getError();
            return new ResponseEntity<>(error, HttpStatusCode.valueOf(error.getError().getCode()));
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (AccessDeniedException e){
            throw e;
        } catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Relationships")
    @Operation(
            summary = "List relationship types",
            description = """
                    Return every relationship type your tenant has defined (`FLOWS_TO`,
                    `CONNECTS_TO`, `PART_OF`, etc.). Relationship types are created on demand
                    by `POST /resources/create` when you first use a new type name, or
                    explicitly via `POST /edges/types/create`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Every relationship type available to your tenant.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = RelationshipTypeDataWrapper.class)
            ))
    @RequestMapping(value = "/types", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> getRelationTypes(){
        List<RelationshipType> typeList = relationshipTypeRepository.findAll();
        DataWrapper<RelationshipType> items = new DataWrapper<>();
        items.setItems(typeList);
        return new ResponseEntity<>(items, HttpStatus.OK);
    }

    @Tag(name = "Relationships")
    @Operation(
            summary = "Create relationship types",
            description = """
                    Register new relationship type names up-front. Relationship types are
                    normally auto-created the first time they're used in
                    `POST /resources/create`; this endpoint is for admin flows that want to
                    pre-seed the catalog or attach a description / i18n code to a type.

                    Type names are case-insensitive and normalised to uppercase snake case
                    (`Flows To` → `FLOWS_TO`).
                    """
    )
    @ApiResponse(responseCode = "200", description = "The relationship types were created.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = RelationshipTypeDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description =
            "A type name was rejected — for example one that normalises down to nothing.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description =
            "A relationship type with the same (case-insensitive) name already exists.", content = @Content)
    @RequestMapping(value = "/types/create", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> createRelationTypes(
            @Schema(implementation = RelTypeFormDataWrapper.class)
            @RequestBody @Valid DataWrapper<RelTypeForm> apiReqData){
        try {
            Collection<RelationshipType> typeList = edgeService.createRelationshipTypes(apiReqData);
            DataWrapper<RelationshipType> items = new DataWrapper<>();
            items.setItems(typeList);
            return new ResponseEntity<>(items, HttpStatus.OK);
        }
        // Defence in depth: @NotBlank on RelTypeForm.name rejects empty/whitespace names before
        // we get here, but a name that normalises down to nothing (e.g. only symbols) is caught
        // by RelationshipType.setName. Surface it as a 400 warning rather than a 500.
        catch (IllegalArgumentException e) {
            log.warn("Rejected relationship type creation: {}", e.getMessage());
            BadRequestError error = new BadRequestError()
                    .setMessage(e.getMessage())
                    .addFieldError("name", e.getMessage());
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        // The relationship_hash_key unique constraint — a type with this (case-insensitive) name
        // already exists. Surface it as a 409 rather than a bare 500, matching /edges/create.
        catch (DataIntegrityViolationException dve){
            log.warn("Rejected relationship type creation: {}", dve.getMessage());
            return new ResponseEntity<>(BuildErrorResponse.createDataIntegrityViolationError(dve), HttpStatus.CONFLICT);
        }
    }

    @Tag(name = "Relationships")
    @Operation(
            summary = "Delete relationships",
            description = """
                    Delete one or more relationships by `id`. Deletes the link only — the
                    resources at each end stay intact.

                    ### Idempotent
                    Unknown ids are silently skipped.
                    """
    )
    @ApiResponse(responseCode = "204", description = "The relationships were deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "409", description =
            "Concurrency conflict — another request modified or deleted the relationship " +
                    "between read and write. Clients should re-fetch the current state and retry.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ConflictError.class)
            ))
    @RequestMapping(value = "/delete",
            method = {RequestMethod.POST, RequestMethod.DELETE})
    public ResponseEntity<?> delete(
            @Schema(implementation = IdCollectionDataWrapper.class)
            @RequestBody @Valid DataWrapper<IdCollection> apiReqData){
        try{
            edgeService.deleteRelationships(apiReqData);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler — the broad
        // Exception catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        }
        // Refusing the delete because it would strand node(s) from the graph root is a business
        // rule, not a crash: the exception carries the offending resources (see
        // ResourceService#delete). Surface it as a 400 with that list instead of a bare 500.
        catch (ResourceDeleteException e){
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (AccessDeniedException e){
            throw e;
        } catch (Exception e){
            log.error(e.getMessage(), e);
            return new ResponseEntity<>("error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
