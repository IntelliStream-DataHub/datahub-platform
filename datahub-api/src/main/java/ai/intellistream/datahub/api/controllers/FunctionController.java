// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.function.UpdateFunctionForm;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.controllers.errors.*;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.FunctionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.services.FunctionService;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.FunctionRetreiver;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.datafilters.FunctionFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import ai.intellistream.datahub.api.controllers.errors.schema.DeleteRefusedProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.ValidationProblem;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
                    schema = @Schema(implementation = ValidationProblem.class)
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
            summary = "Fetch functions by id or externalId",
            description = """
                    Look up several functions at once. Each entry needs either a numeric `id`, an
                    `externalId`, or both.

                    Ids that do not exist, are not functions, or are not readable by this caller are
                    left out of the response rather than failing the call.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The functions that were found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FunctionDataWrapper.class)
            ))
    @PostMapping(path = "/byids", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> findByIdList(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Identifiers of the functions to look up.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "id": 5677892 },
                                        { "externalId": "fn_rolling_average" }
                                      ]
                                    }
                                    """)
                    )
            )
            @Schema(implementation = IdCollectionDataWrapper.class)
            @Valid @RequestBody DataWrapper<IdCollection> apiReqData) {
        Set<Long> ids = apiReqData.getItems().stream()
                .map(IdCollection::getId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> externalIds = apiReqData.getItems().stream()
                .map(IdCollection::getExternalId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        return ResponseEntity.ok(functionService.byIds(ids, externalIds));
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Filter functions",
            description = """
                    Return the functions that match a set of filters. All filters are combined with
                    AND — a function must match every filter you supply to be included.

                    Every list field also accepts a bare value, so `"source": "sap"` and
                    `"source": ["sap"]` mean the same thing.

                    Supported filters are the criteria every node type shares:
                    - `dataSetId` — functions of those data sets **and every data set beneath them**
                      in the hierarchy. Each entry names a data set by `id` or `externalId`.
                    - `id` / `externalId` / `name` / `source` / `labels` — `externalId`, `name` and
                      `source` are pattern lists: `*` and `%` are wildcards, `_` is literal, matching
                      is case-insensitive, and an entry without a wildcard matches exactly. `labels`
                      must **all** be present.
                    - `metadata` — every entry must be present. A **null value matches the key
                      alone**, whatever it carries.
                    - `createdTime` / `lastUpdatedTime` — inclusive `min`/`max` instants.

                    Data sets you lack read access to are silently omitted. `limit` defaults to 1000
                    and may not exceed 10 000; results come newest created first. For free-text
                    lookups use `POST /functions/search` instead.

                    `sort` takes one property with an `order` of `asc` or `desc`; `id` is always
                    appended so the order is total. The response carries `nextCursor` when there may
                    be more: send it back as `cursor`, with the same `sort` it came from. Keyset
                    paging, not `OFFSET`, so a deep page costs what a shallow one does.
                    """
    )
    @ApiResponse(responseCode = "200",
            description = "The functions that match every supplied filter, newest first.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FunctionDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "The request failed validation.",
            content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ValidationProblem.class)
            ))
    @PostMapping(path = "/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> filter(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Filter criteria and optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {
                                      "limit": 100,
                                      "filter": {
                                        "name": ["rolling*"],
                                        "labels": ["AGGREGATION"],
                                        "dataSetId": [{ "id": "12" }]
                                      }
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody FunctionRetreiver apiReqData) {
        return ResponseEntity.ok(functionService.filter(apiReqData));
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Search functions",
            description = """
                    Free-text search across functions, ranked by how well each one matches the
                    phrase. The optional `filter` takes the same criteria as
                    `POST /functions/filter` and is ANDed with the phrase.

                    Matching rules may evolve over time; don't rely on this endpoint for equality
                    tests — use `POST /functions/byids` for that.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Functions ranked by how well they matched.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FunctionDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "The request failed validation.",
            content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ValidationProblem.class)
            ))
    @PostMapping(path = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Search phrase, optional filter, optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {
                                      "search": { "query": "rolling average" },
                                      "filter": { "labels": ["AGGREGATION"] },
                                      "limit": 50
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody SearchBody<FunctionFilter> apiReqData) {
        return ResponseEntity.ok(functionService.search(apiReqData));
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Update function",
            description = """
                    Update one or more functions (and any relations). Only the fields named in each
                    entry's `update` block are changed.

                    Every entry must name a function. An id or `externalId` belonging to some other
                    node type is a 404, like one that does not exist, and nothing in the batch is
                    written.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Function(s) updated.")
    @ApiResponse(responseCode = "404", description = "An entry is not a function, or does not exist. Nothing was updated.",
            content = @Content(mediaType = "application/problem+json"))
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
            @RequestBody GraphDataWrapper<UpdateFunctionForm, UpdateRelForm> apiReqData) throws PulsarClientException {
        GraphDataWrapper<NodeModel, EdgeProxy> results = functionService.update(apiReqData);
        return new ResponseEntity<>(results, HttpStatus.OK);
    }

    @Tag(name = "Functions")
    @Operation(
            summary = "Delete function",
            description = """
                    Delete one or more functions by id or externalId. Deleting a function removes all
                    of its relationships; the delete is rejected if it would strand a surviving node.

                    Ids that do not exist or are not functions are skipped, not deleted.
                    """
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
