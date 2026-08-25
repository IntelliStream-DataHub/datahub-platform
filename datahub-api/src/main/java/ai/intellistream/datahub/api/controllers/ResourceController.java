// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.policy.NamingPolicyViolationException;
import ai.intellistream.datahub.api.controllers.errors.*;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.CreateResources;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.ResourceDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.ResourceGraphDataWrapper;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/resources")
@Slf4j
public class ResourceController {

    private final ResourceService resourceService;
    private final Validator validator;

    public ResourceController(ResourceService resourceService, Validator validator){
        this.resourceService = resourceService;
        this.validator = validator;
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Find resource by id",
            description = """
                    Look up a single resource by its numeric server-assigned `id`.

                    If you only know the `externalId`, use `POST /resources/byids` instead —
                    it accepts either kind of identifier.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The resource was found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResourceDataWrapper.class),
                    examples = @ExampleObject(value = """
                            {
                              "items": [
                                {
                                  "id": 5677892,
                                  "externalId": "klp_pipe_ws_a1212_dl",
                                  "name": "klp pipe ws-a1212-dl",
                                  "description": "Water stream pipe",
                                  "labels": ["PIPE"],
                                  "dataSetId": 12,
                                  "source": "dolphin_rex_pipes",
                                  "metadata": { "work_order": "wo-sap-12344" }
                                }
                              ]
                            }
                            """)
            ))
    @ApiResponse(responseCode = "404", description =
            "No resource with this `id` exists, or it belongs to a tenant you can't read. " +
                    "Double-check the id and your API token's tenant.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "Could not find resource with id: 42")
            ))
    @RequestMapping(value = "/{id}", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> get(@Parameter(description = "Numeric id of the resource.", example = "5677892") @PathVariable("id") Long id){
        // Not-found — and, deliberately, no-read-access — surface as ObjectNotFoundException and are
        // mapped to 404 by ObjectNotFoundExceptionHandler so a hidden resource's existence isn't leaked.
        return new ResponseEntity<>(resourceService.get(id), HttpStatus.OK);
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Fetch resources connected to a starting resource",
            description = """
                    Starting from one resource, walk outward along its relationships and return
                    everything reachable within a given `depth`.

                    Useful for "what is connected to this asset?" style questions — for example,
                    *show me every pipe, valve, and sensor attached to this processing unit*.

                    Identify the starting resource with either its numeric `id` or its
                    `externalId`. `depth` controls how many relationship hops to follow; keep it
                    small (1–3) unless you know the graph is sparse, because the result set grows
                    quickly.

                    Nodes come back typed by their type-label (a time series as a Timeseries, a
                    data set as a data set, and so on). The graph stores only a subset of each
                    node's columns, so graph-sourced nodes are typed but sparsely populated —
                    a Timeseries here carries no `unit` or `securityCategories`; fetch it by id
                    for the full record.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Returns the starting resource plus every resource and relationship reached within `depth` hops.",
                content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ResourceNetwork.class)
    ))
    @ApiResponse(responseCode = "404", description =
            "The starting resource was not found. Check `id` / `externalId` and your tenant.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "Could not find resource with id: 42")
            ))
    @RequestMapping(value = "/fetch-related", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> fetchRelatedResources(@RequestBody RelatedResourcesForm form) {
        try{
            ResourceNetwork network = resourceService.fetchRelatedResources(form);
            return new ResponseEntity<>(network, HttpStatus.OK);
        } catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Find the nearest resources of a given label",
            description = """
                    Breadth-first from a starting resource (numeric `id`), return the closest `limit`
                    nodes carrying one of `endLabels` (e.g. `["TIMESERIES"]`) plus the sub-graph that
                    connects them. The cap is on matching END-nodes, not on hop depth or total node
                    count — so "the 10 nearest time series" is exact however many intermediate nodes
                    lie between them. `excludedLabels` (e.g. `["POLICY"]`) are never traversed or
                    returned; `relationshipTypes` restricts which edges may be followed.

                    Nodes come back typed by their type-label (a time series as a Timeseries, a
                    data set as a data set, and so on). The graph stores only a subset of each
                    node's columns, so graph-sourced nodes are typed but sparsely populated —
                    a Timeseries here carries no `unit` or `securityCategories`; fetch it by id
                    for the full record.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The nearest matching nodes plus every node and relationship on the paths to them.",
                content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ResourceNetwork.class)
    ))
    @ApiResponse(responseCode = "404", description = "The starting resource was not found. Check `id` and your tenant.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "Could not find resource with id: 42")
            ))
    @RequestMapping(value = "/fetch-nearest", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> fetchNearestResources(@RequestBody FetchNearestResourcesForm form) {
        try{
            ResourceNetwork network = resourceService.fetchNearestRelatedResources(
                    form.getId(), form.getEndLabels(), form.getLimit(),
                    form.getRelationshipTypes(), form.getExcludedLabels());
            return new ResponseEntity<>(network, HttpStatus.OK);
        } catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Find multiple resources by id or externalId",
            description = """
                    Look up several resources in one call. Each entry in `items[]` carries
                    either a numeric `id`, an `externalId`, or both — mix and match freely.

                    Resources that don't exist are simply omitted from the response; the call
                    doesn't fail. Compare the returned `items[]` against what you asked for to
                    detect missing ones.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The resources that were found. Missing ones are silently left out.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ResourceDataWrapper.class)
    ))
    @RequestMapping(value = "/byids", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> findByIdList(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Identifiers of the resources to look up. Each entry needs either `id` or `externalId`.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "id": 5677892 },
                                        { "externalId": "klp_valve_v9" }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody @Schema(implementation = IdCollectionDataWrapper.class)
            DataWrapper<IdCollection> apiReqData
    ){
        try{
            var idList = apiReqData.getItems().stream().map(IdCollection::getId).filter(Objects::nonNull).collect(Collectors.toSet());
            var externalIdList = apiReqData.getItems().stream().map(IdCollection::getExternalId).filter(Objects::nonNull).collect(Collectors.toSet());
            DataWrapper<NodeModel> resources = resourceService.findAllByIdAndExternalId(idList, externalIdList);
            return new ResponseEntity<>(resources, HttpStatus.OK);
        } catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "List resources matching filter criteria",
            description = """
                    Return the resources that match a set of filters. All filters are combined
                    with AND — a resource must match every filter you supply to be included.

                    Supported filters:
                    - `name` — case-insensitive substring match. Accepts `%` as a wildcard.
                    - `id` — exact numeric id.
                    - `externalId` — exact externalId.
                    - `isRoot` — `true` or `false`.
                    - `dataSetId` — only resources belonging to any of these datasets.
                    - `metadata` — every key/value in this object must be present on the resource.
                    - `source` — case-insensitive substring match on `source`.
                    - `createdTime.min` / `createdTime.max` — ISO-8601 timestamp bounds (inclusive).
                    - `lastUpdatedTime.min` / `lastUpdatedTime.max` — same for last-updated.

                    ### This is the generic node query
                    Unlike `/datasets/filter`, `/timeseries/filter` and `/events/filter`, which each
                    answer for one type, this endpoint spans **every node type** — assets,
                    timeseries, functions, resources, data sets and policies share one table and one
                    set of criteria. Narrow it with `nodeType` (`["resource", "timeseries"]`) when
                    you want only some; omit it for all. Every node carries its type as a label, so
                    you can tell what came back.

                    Every list field also accepts a bare value, so `"name": "pipe*"` and
                    `"name": ["pipe*"]` mean the same thing. `externalId`, `name` and `source`
                    are pattern lists: `*` and `%` are wildcards, `_` is literal, matching is
                    case-insensitive, and an entry without a wildcard matches exactly. `labels`
                    must **all** be present, and a null `metadata` value matches the key alone.

                    ### Ordering and paging
                    Results come newest created first, capped by `limit` (default 1000, max 10000).
                    `sort` takes one property — `id`, `externalId`, `name`, `source`,
                    `description`, `createdTime`, `lastUpdatedTime` or `dataSetId` — with `order` of
                    `asc` or `desc`; `id` is always appended so the order is total and a page
                    boundary can never fall inside a run of equal values. Nulls sort last ascending
                    and first descending.

                    The response carries `nextCursor` when there may be more. Send it back as
                    `cursor` for the following page and keep going while it is present. This is
                    keyset paging, not `OFFSET`: each page is a range seeked to rather than rows
                    counted and thrown away, so a deep page costs what a shallow one does, and rows
                    written elsewhere cannot shift the walk into repeating or skipping one. Send the
                    cursor back with **the same `sort` it came from** — a cursor is a position in
                    one particular order, so continuing it under another is rejected with `400`
                    rather than answered with a page that is quietly wrong.

                    A cursor that cannot be read — truncated, edited, or from an older format — is
                    rejected with `400` too, rather than quietly returning the first page: a client
                    that pages by echoing back what it was given would otherwise loop on page one
                    forever, never advancing and never told anything was wrong.
                    """
    )
    @ApiResponse(responseCode = "200",
            description = "The resources that match every supplied filter. Empty `items[]` means nothing matched.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResourceDataWrapper.class)
            )
    )
    @RequestMapping(value = "/filter", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
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
                                        "name": ["pipe*"],
                                        "externalId": ["klp_pipe_*"],
                                        "labels": ["PIPE"],
                                        "dataSetId": [{ "id": "12" }],
                                        "metadata": { "work_order": "wo-sap-12344" },
                                        "createdTime": {
                                          "min": "2026-01-01T00:00:00Z"
                                        }
                                      }
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody ResourceRetreiver apiReqData){
        try{
            Set<ConstraintViolation<ResourceRetreiver>> errors = validator.validate(apiReqData);
            if (!errors.isEmpty()) {
                throw new ConstraintViolationException(errors);
            }
            DataWrapper<NodeModel> items = resourceService.filter(apiReqData);
            return new ResponseEntity<>(items, HttpStatus.OK);
        } catch (ConstraintViolationException e){
            log.error(e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Full-text search resources",
            description = """
                    Free-text search across **every node type** — assets, timeseries, functions,
                    resources, data sets and policies — the same breadth as
                    `POST /resources/filter`. The phrase is matched against `name`, `externalId`
                    and `description`. Matching is fuzzy and word-aware: search for `pipe` and
                    you'll also find `pipes`, `piping`, and multi-word names containing the term.

                    ### Narrowing the results
                    `filter` is optional and takes the same criteria as `POST /resources/filter`.
                    It only ever *removes* matches — the phrase decides what the candidates are.
                    Use it to say "pipes, but only in this data set" or "pipes, but only
                    timeseries". Omit it for no narrowing.

                    If you don't need a phrase at all, use `POST /resources/filter`: a structured
                    query on its own is faster and more predictable than one bolted to a search.

                    `limit` caps the result size (default 100, max 1000).

                    ### Result order
                    Ranked by relevance (`ts_rank`), strongest match first, with `id` as a
                    tie-break so equal-scoring rows keep a stable order and repeated identical
                    requests agree. Ranking means the database scores and sorts every match before
                    applying `limit`, so a very broad phrase costs more than a narrow one.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Resources ranked by how well they match the search phrase.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResourceDataWrapper.class)
            )
    )
    @ApiResponse(responseCode = "400", description =
            "The request failed validation — usually a missing or too-short `query`. Response " +
                    "lists the offending fields.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            )
    )
    @RequestMapping(value = "/search", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> get(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Search phrase, optional filter, optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {
                                      "search": { "query": "pipe" },
                                      "filter": { "nodeType": ["resource"], "labels": ["Asset"] },
                                      "limit": 50
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody SearchBody<ResourceFilter> form){
        // No manual validator.validate here: @Valid on the body already rejected an invalid form
        // before this method ran, so the hand-rolled pass could only ever re-check what had
        // already passed — and its bare-string 400 disagreed with the shape @Valid produces.
        try{
            DataWrapper<NodeModel> items = resourceService.search(form);
            return new ResponseEntity<>(items, HttpStatus.OK);
        }
        catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Create resources and/or relationships",
            description = """
                    Create one or more **resources** and the **relationships** that connect them,
                    in a single request.

                    A resource is any object you want to track: a physical asset, a document,
                    a measurement point, and so on. A relationship is a directed link from one
                    resource to another (for example *pipe flows to valve*).

                    ### What you send
                    - `nodes[]` — the resources to create. Each resource must have a unique
                      `externalId` within your tenant, a `name`, and at least one `labels` entry.
                    - `relations[]` — optional links between resources. You can reference resources
                      being created in the same request by their `externalId`, or link to resources
                      that already exist.

                    ### What you get back
                    The same resources and relations, now with server-assigned numeric `id`s
                    you can use in later calls.

                    ### All-or-nothing
                    If any one resource or relation in the request fails validation, none of them
                    are created. Fix the offending entry and resend.
                    """
    )
    @ApiResponse(responseCode = "201", description = "Resources and relationships were created.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResourceGraphDataWrapper.class),
                    examples = @ExampleObject(
                            name = "Two resources and one relationship",
                            value = """
                                    {
                                      "nodes": [
                                        {
                                          "id": 5677892,
                                          "externalId": "klp_pipe_ws_a1212_dl",
                                          "name": "klp pipe ws-a1212-dl",
                                          "description": "Water stream pipe",
                                          "labels": ["PIPE"],
                                          "dataSetId": 12,
                                          "source": "dolphin_rex_pipes",
                                          "metadata": { "work_order": "wo-sap-12344" }
                                        },
                                        {
                                          "id": 5677893,
                                          "externalId": "klp_valve_v9",
                                          "name": "KLP valve V9",
                                          "labels": ["VALVE"]
                                        }
                                      ],
                                      "relations": [
                                        {
                                          "id": 341,
                                          "start": 5677892,
                                          "end": 5677893,
                                          "type": "FLOWS_TO"
                                        }
                                      ]
                                    }
                                    """
                    )
            ))
    @ApiResponse(responseCode = "400", description =
            "The request has a problem the server could spot before saving anything. " +
                    "Typical causes: missing required field, `externalId` too short or with " +
                    "forbidden characters, referenced `dataSetId` doesn't exist, or a relation " +
                    "points at a resource that isn't in the request and doesn't exist. The " +
                    "`fields` list tells you which input was wrong.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
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
    @ApiResponse(responseCode = "409", description =
            "A resource with one of the `externalId`s you sent already exists in your tenant. " +
                    "The `duplicated` list tells you which ones. Either pick a different " +
                    "`externalId`, or use `POST /resources/update` to modify the existing resource.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DuplicateError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 409,
                                "message": "External id already exists.",
                                "duplicated": [
                                  { "externalId": "klp_pipe_ws_a1212_dl" }
                                ]
                              }
                            }
                            """)
            ))
    @ApiResponse(responseCode = "422", description =
            "One or more fields failed validation rules (length limits, character set, " +
                    "required-ness). Response lists the offending fields per entry.",
            content = @Content(
                    schema = @Schema(implementation = DataWrapper.class)
            ))
    @PostMapping(
            path = "/create",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "The resources and relations to create.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreateResources.class),
                            examples = @ExampleObject(
                                    name = "Pipe connected to a valve",
                                    value = """
                                            {
                                              "nodes": [
                                                {
                                                  "externalId": "klp_pipe_ws_a1212_dl",
                                                  "name": "klp pipe ws-a1212-dl",
                                                  "description": "Water stream pipe",
                                                  "labels": ["PIPE"],
                                                  "dataSetId": 12,
                                                  "source": "dolphin_rex_pipes",
                                                  "metadata": { "work_order": "wo-sap-12344" }
                                                },
                                                {
                                                  "externalId": "klp_valve_v9",
                                                  "name": "KLP valve V9",
                                                  "labels": ["VALVE"]
                                                }
                                              ],
                                              "relations": [
                                                {
                                                  "fromExternalId": "klp_pipe_ws_a1212_dl",
                                                  "toExternalId": "klp_valve_v9",
                                                  "relationshipType": "FLOWS_TO"
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody
            @Schema(implementation = CreateResources.class)
            GraphDataWrapper<NodeModel, RelForm> apiReqData
    ){
        try{
            Set<ConstraintViolation<GraphDataWrapper<NodeModel, RelForm>>> errors = validator.validate(apiReqData);
            if (!errors.isEmpty()) {
                throw new ConstraintViolationException(errors);
            }
            GraphDataWrapper<NodeModel, EdgeProxy> results = resourceService.create(apiReqData);
            return new ResponseEntity<>(results, HttpStatus.CREATED);
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException dve){
            var e = BuildErrorResponse.createDataIntegrityViolationError(dve);
            return new ResponseEntity<>(e, HttpStatus.CONFLICT);
        }
        catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (NamingPolicyViolationException e) {
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response. The
            // BadRequestException catch below would otherwise flatten it into the generic error
            // envelope and lose the per-item `violations` list, which is the useful part.
            throw e;
        } catch (BadRequestException e){
            var error = e.getError();
            return new ResponseEntity<>(error, HttpStatusCode.valueOf(error.getError().getCode()));
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (org.springframework.security.access.AccessDeniedException e){
            throw e;
        }
        catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Update resources and/or relationships",
            description = """
                    Change fields on existing resources or relationships. Identify each entry
                    by either `id` or `externalId` — whichever you have.

                    Only the fields you include in the `update` block are changed. Fields you
                    leave out keep their current value. Every updatable field is a small object
                    with these options:

                    - `"set"` — replace the field with this value.
                    - `"setNull": true` — clear the field. Rejected with a 400 on `name` and
                      `externalId`, which every resource must have; use `set` to change them.
                    - `"add"` / `"remove"` — for collection fields (`metadata`, `labels`), add
                      or remove entries while leaving the rest untouched.

                    ### All-or-nothing
                    If any entry fails validation, nothing is saved. Fix the offending entry and
                    resend.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The resources and relationships after the update, with current values.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResourceGraphDataWrapper.class),
                    examples = @ExampleObject(value = """
                            {
                              "nodes": [
                                {
                                  "id": 5677892,
                                  "externalId": "klp_pipe_ws_a1212_dl",
                                  "name": "klp pipe ws-a1212-dl (renamed)",
                                  "description": "Water stream pipe — primary loop",
                                  "labels": ["PIPE", "CRITICAL"],
                                  "metadata": { "work_order": "wo-sap-12344", "inspected_by": "olav" }
                                }
                              ],
                              "relations": []
                            }
                            """)
            ))
    @ApiResponse(responseCode = "400", description =
            "The request has a problem the server could spot before saving anything. " +
                    "Typical causes: neither `id` nor `externalId` supplied, the targeted " +
                    "resource doesn't exist, or an update rule is malformed (`set` and `setNull` " +
                    "both present on the same field). The `fields` list tells you which input " +
                    "was wrong.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 400,
                                "message": "Resource cannot be found.",
                                "fields": [
                                  { "externalId": "klp_pipe_ws_a1212_dl", "id": "null" }
                                ]
                              }
                            }
                            """)
            ))
    @ApiResponse(responseCode = "409", description =
            "Someone else changed or deleted the resource while your update was in flight. " +
                    "Your write was not applied. Re-fetch the resource with `POST /resources/byids` " +
                    "and retry the update with fresh state.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 409,
                                "cause": "concurrency",
                                "message": "The resource was modified or removed by another request. Re-read and retry."
                              }
                            }
                            """)
            ))
    @ApiResponse(responseCode = "429", description = "Too many requests — back off and retry.",
            content = @Content(
                    schema = @Schema(implementation = DataWrapper.class)
            ))
    @PostMapping(
            path = "/update",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> update(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Resources and/or relationships to update. Only fields you name in `update` are changed.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(
                                    name = "Rename and add metadata",
                                    value = """
                                            {
                                              "nodes": [
                                                {
                                                  "externalId": "klp_pipe_ws_a1212_dl",
                                                  "update": {
                                                    "name": { "set": "klp pipe ws-a1212-dl (renamed)" },
                                                    "description": { "set": "Water stream pipe — primary loop" },
                                                    "metadata": { "add": { "inspected_by": "olav" } },
                                                    "labels": { "add": ["CRITICAL"] }
                                                  }
                                                }
                                              ],
                                              "relations": []
                                            }
                                            """
                            )
                    )
            )
            @RequestBody GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData){
        try{
            GraphDataWrapper<Resource, EdgeProxy> results = resourceService.update(apiReqData);
            return new ResponseEntity<>(results, HttpStatus.OK);
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (NamingPolicyViolationException e) {
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response. The
            // BadRequestException catch below would otherwise flatten it into the generic error
            // envelope and lose the per-item `violations` list, which is the useful part.
            throw e;
        } catch (BadRequestException e) {
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler — the broad
        // RuntimeException catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (org.springframework.security.access.AccessDeniedException e){
            throw e;
        }
        catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Delete resources",
            description = """
                    Delete one or more resources. Identify each one by `id`, `externalId`, or
                    both. Unknown identifiers are silently skipped — the call succeeds as long
                    as no safety check fails.

                    ### Connectivity must be preserved
                    Deleting a resource removes **all** of its relationships (inbound and
                    outbound) along with it. The delete is rejected with `400` if doing so would
                    leave any surviving resource disconnected from a root resource — i.e. if it
                    would split off part of the graph. To remove such a node, include the nodes it
                    would strand in the same delete, or first re-attach them via another path. The
                    response names the resources that would be stranded.

                    ### Idempotent
                    Calling delete again for a resource that's already gone is a no-op and
                    returns `204`.

                    ### All-or-nothing
                    A single safety-check failure rolls back the whole batch — nothing is
                    deleted unless everything can be.
                    """
    )
    @ApiResponse(responseCode = "204", description = "The targeted resources (and any connected relationships pointing AT them) were deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "400", description =
            "Something prevents the delete from being safe. Most commonly the delete would " +
                    "disconnect part of the graph from its root — the response names the " +
                    "resources that would be stranded so you can include them or re-attach them.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 400,
                                "message": "Deleting this selection would disconnect resource(s) [42, 43] from the graph root. Include them in the deletion or keep a connecting path."
                              }
                            }
                            """)
            ))
    @ApiResponse(responseCode = "409", description =
            "Someone else changed or deleted one of the targeted resources while your delete " +
                    "was in flight. No resources were removed. Re-fetch state and retry.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 409,
                                "cause": "concurrency",
                                "message": "The resource was modified or removed by another request. Re-read and retry."
                              }
                            }
                            """)
            ))
    @RequestMapping(
            path = "/delete",
            produces = MediaType.APPLICATION_JSON_VALUE,
            method = {RequestMethod.POST, RequestMethod.DELETE}
    )
    public ResponseEntity<?> delete(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Identifiers of the resources to delete. Each entry needs either `id` or `externalId`.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "externalId": "klp_valve_v9" },
                                        { "id": 5677892 }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody @Schema(implementation = IdCollectionDataWrapper.class)
                                        DataWrapper<IdCollection> form
    ){
        try{
            var entities = new GraphDataWrapper<Resource, EdgeProxy>();
            form.getItems().forEach(it -> {
                Resource r = new Resource();
                if(it.getId() != null){
                    r.setId(it.getId());
                    entities.getNodes().add(r);
                } else if(it.getExternalId() != null){
                    r.setExternalId(it.getExternalId());
                    entities.getNodes().add(r);
                }
            });
            resourceService.delete(entities);
            return new ResponseEntity<>("", HttpStatus.NO_CONTENT);
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (ResourceDeleteException e){
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler — the broad
        // RuntimeException catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (org.springframework.security.access.AccessDeniedException e){
            throw e;
        }
        catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

}
