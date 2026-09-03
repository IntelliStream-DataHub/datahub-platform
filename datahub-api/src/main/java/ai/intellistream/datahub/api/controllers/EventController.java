// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.LimitException;
import ai.intellistream.datahub.api.controllers.errors.*;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.EventCountResponse;
import ai.intellistream.datahub.api.responses.swaggerdto.StringValuesDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.EventDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.UUIDAndExternalIdCollectionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.UpdateEventDataWrapper;
import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.events.EventFilter;
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
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/events")
@Slf4j
public class EventController {

    private final EventService eventService;
    private final Validator validator;

    public EventController(EventService eventService, Validator validator){
        this.eventService = eventService;
        this.validator = validator;
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Find event by id",
            description = """
                    Look up a single event by its UUID.

                    An event is a timestamped thing-that-happened: an alarm triggered, a
                    maintenance job ran, a measurement crossed a threshold. Events are
                    immutable in practice — prefer creating a new event to updating one.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The event was found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EventDataWrapper.class)
            ))
    @ApiResponse(responseCode = "404", description =
            "No event with this id exists, or it belongs to a tenant you can't read.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "Could not find event with id: 0195f3a2-...")
            ))
    @RequestMapping(value = "/{id}", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> get(@NotNull @Parameter(description = "UUID of the event to look up.", example = "0195f3a2-4c1b-7f9e-9c3a-1b2d4e6f8a90") @PathVariable("id") String id){
        // findById folds the caller's read-ACL into the query, so an event that is missing OR
        // outside the caller's readable datasets throws ObjectNotFoundException → 404 (no existence leak).
        return new ResponseEntity<>(eventService.findById(id), HttpStatus.OK);
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Find events by id or externalId",
            description = """
                    Look up several events in one call. Each entry in `items[]` carries either
                    a UUID `id`, an `externalId`, or both — mix freely.

                    Events that don't exist are silently omitted. Hard-capped at 10 000 ids
                    per request.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The events that were found. Missing ones are silently left out.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EventDataWrapper.class)
            ))
    @RequestMapping(value = "/byids", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> findByIdList(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UUIDAndExternalIdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "id": "0193a4b5-6c7d-7e8f-9012-3456789abcde" },
                                        { "externalId": "alarm_pipe_overpressure_2026_04" }
                                      ]
                                    }
                                    """)
                    )
            )
            @Schema(implementation = UUIDAndExternalIdCollectionDataWrapper.class)
            @RequestBody
            DataWrapper<UUIDAndExternalIdCollection> apiReqData
    ){
        try{
            if(apiReqData.getItems().size() > 10000){
                throw new IllegalArgumentException("Maximum 10000 ids allowed");
            }
            DataWrapper<EventModel> events = eventService.findAllByIdAndExternalId(apiReqData.getItems());
            return new ResponseEntity<>(events, HttpStatus.OK);
        } catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Filter events",
            description = """
                    Return events that match a set of filters. All filters are combined with
                    AND — an event must match every filter you supply to be included.

                    Every list field also accepts a bare value, so `"type": "alarm"` and
                    `"type": ["alarm"]` mean the same thing.

                    Common filters:
                    - `type` / `subType` / `status` / `externalId` / `source` — pattern
                      lists. `*` and `%` are wildcards, `_` is literal, and an entry without a
                      wildcard matches exactly. Entries within a list OR together, so
                      `"type": ["alarm", "warning"]` is one call. Matching is case-insensitive
                      except for a **literal `externalId` entry**, which resolves through the
                      stored hash and so matches the id verbatim — add a trailing `*` if you want
                      case-insensitive matching there too.
                    - `eventTime.min` / `eventTime.max` — bound when the event occurred (epoch ms).
                      `createdTime` and `lastUpdatedTime` take the same shape.
                    - `dataSetId` — restrict to events in these data sets, each given by `id` or
                      `externalId`: `[{"id": 43}, {"externalId": "data_set_sap"}]`. A data set
                      stands in for **everything beneath it** in the hierarchy, so naming a parent
                      covers its children; an `externalId` naming no data set contributes nothing.
                      Omit the field for no data set restriction; an explicit `[]` matches nothing.
                    - `relatedResources` — events attached to specific resources. Each entry takes
                      an `id`, an `externalId`, or both; the event must be attached to all of them.
                    - `metadata` — all entries must be present. A **null value matches the key
                      alone**, so `{"health": null}` finds anything tagged `health`.

                    ### Ordering and paging
                    Results are ordered by `eventTime`, then by `id` to break ties, and capped by
                    `limit` (default 1000, max 10000). `sort` takes one property — `eventTime`,
                    `createdTime`, `lastUpdatedTime`, `externalId`, `type`, `subType`, `status`,
                    `source` or `dataSetId` — with `order` of `asc` or `desc`. `id` is always
                    appended, so the order is total and a page boundary can never fall inside a run
                    of equal values.

                    The response carries `nextCursor` when there may be more. Send it back as
                    `cursor` to get the following page, and keep going while it is present — an
                    absent `nextCursor` means the walk is done. This is keyset paging, not `OFFSET`:
                    each page is a range the index seeks straight to rather than rows counted and
                    thrown away, so page 100 costs what page 1 does, and rows written elsewhere in
                    the table cannot shift the walk into repeating or skipping one.

                    Send the cursor back with **the same `sort` it came from**. A cursor is a
                    position in one particular order, so continuing it under a different sort is
                    rejected with `400` rather than answered with a page that is quietly wrong.
                    Sorting by `subType` or `status` cannot be paged at all: those fields may be
                    empty, and a keyset boundary on them would skip the events that have no value.

                    A cursor that cannot be read — truncated, edited, or from an older format — is
                    rejected with `400` rather than quietly returning the first page, which would
                    loop a client that pages by echoing back what it was given.

                    Note the default differs from `/datasets/filter`, `/resources/filter` and
                    `/timeseries/filter`, which return newest-created-first: events are partitioned
                    by event time, so ordering by anything else would sort every matched row.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Events that match every supplied filter.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EventDataWrapper.class)
            ))
    @RequestMapping(value = "/filter", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> filter(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {
                                      "limit": 100,
                                      "filter": {
                                        "type": ["alarm", "warning"],
                                        "status": ["OPEN"],
                                        "externalId": ["work_order_*"],
                                        "dataSetId": [{ "id": "43" }, { "externalId": "data_set_sap" }],
                                        "relatedResources": [{ "externalId": "klp_pipe_ws_a1212_dl" }],
                                        "metadata": { "health": null },
                                        "eventTime": { "min": 1745241600000 }
                                      }
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody EventRetreiver apiReqData){
        try{
            DataWrapper<EventModel> items = eventService.filter(apiReqData);
            return new ResponseEntity<>(items, HttpStatus.OK);
        } catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Full-text search events",
            description = """
                    Case-insensitive **substring** search across events: the phrase is matched
                    against `externalId`, `description` and the event's metadata *values*.

                    Unlike the resource, data set and timeseries searches this one is not
                    word-aware and does not rank — searching `pump` finds `pump` and `pumps` but
                    not `pumping`, and results come back newest first by `eventTime`. Events live
                    in ClickHouse rather than the node table and have no full-text index.

                    ### Narrowing the results
                    `filter` is optional and takes the same criteria as `POST /events/filter` —
                    `type`, `status`, `eventTime` ranges, `relatedResources`, and the rest. It
                    only ever *removes* matches; the phrase decides what the candidates are.
                    Omit it for no narrowing.

                    If you don't need a phrase at all, use `POST /events/filter`: a structured
                    query on its own is faster and more predictable than one bolted to a search.

                    `limit` caps the result size (default 100, max 1000).
                    """
    )
    @ApiResponse(responseCode = "200", description = "Events ranked by how well they match the search phrase.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EventDataWrapper.class)
            ))
    @RequestMapping(value = "/search", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> search(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Search phrase, optional filter, optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {
                                      "search": { "query": "bearing" },
                                      "filter": { "type": ["Alarm"], "status": ["OPEN"] },
                                      "limit": 50
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody SearchBody<EventFilter> form){
        // No manual validator.validate here: @Valid on the body already rejected an invalid form
        // before this method ran, so the hand-rolled pass could only ever re-check what had
        // already passed — and its bare-string 400 disagreed with the shape @Valid produces.
        try{
            DataWrapper<EventModel> items = eventService.search(form);
            return new ResponseEntity<>(items, HttpStatus.OK);
        }
        catch (ai.intellistream.datahub.errors.ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Create events",
            description = """
                    Create one or more **events**. An event is a timestamped record of
                    something that happened — an alarm, a calibration, a threshold breach.

                    Each event needs:
                    - a unique `externalId` within your tenant,
                    - an `eventTime` (when it happened, epoch ms),
                    - optionally `type` and `subType` for categorization,
                    - optionally `dataSetId` to group with related data,
                    - optionally `relatedResources` to link the event to the resources it
                      concerns. Give each entry an `id`, an `externalId`, or both — the server
                      resolves the missing side and always returns both.

                    ### Servers assigns the id
                    `id` is a UUID generated on the server. Don't send one — it will be
                    ignored. The returned event carries the new id.

                    ### All-or-nothing
                    If any event in the request fails validation, none are created.
                    """
    )
    @ApiResponse(responseCode = "201", description = "The created events, with server-assigned UUIDs.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = EventDataWrapper.class)
    ))
    @ApiResponse(responseCode = "400", description =
            "The request has a problem the server spotted before saving. Typical causes: " +
                    "missing `externalId`, referenced `dataSetId` doesn't exist, a " +
                    "`relatedResources` entry points at a resource that doesn't exist, or its " +
                    "`id` and `externalId` name different resources.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description =
            "An event with one of the `externalId`s already exists. Pick a different one.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DuplicateError.class)
            ))
    @ApiResponse(responseCode = "422", description =
            "One or more fields failed validation rules.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @PostMapping(
            path = "/create",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Events to create.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EventDataWrapper.class),
                            examples = @ExampleObject(
                                    name = "An alarm attached to a pipe",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "externalId": "alarm_pipe_overpressure_2026_04_22_14_30",
                                                  "eventTime": 1745328600000,
                                                  "type": "alarm",
                                                  "subType": "overpressure",
                                                  "description": "Pipe A1212 briefly exceeded 40 bar",
                                                  "relatedResources": [{ "externalId": "klp_pipe_ws_a1212_dl" }],
                                                  "dataSetId": 12,
                                                  "metadata": { "severity": "high" }
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody
                                    @Schema(implementation = EventDataWrapper.class)
                                    DataWrapper<EventModel> apiReqData
    ){
        try{
            DataWrapper<EventModel> results = eventService.create(apiReqData);
            return new ResponseEntity<>(results, HttpStatus.CREATED);
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (BadRequestException e) {
            log.error(e.getMessage(), e);
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (org.springframework.security.access.AccessDeniedException e){
            throw e;
        }
        catch (LimitException e){
            // A limit refusal is an answer, not a fault: without this the catch below
            // flattens it into a 500 and the caller never learns which limit they hit.
            throw e;
        }
        catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Update events",
            description = """
                    Change fields on existing events. Identify each one by UUID `id` or
                    `externalId`. Only fields you name in the `update` block are changed.

                    Uses the standard `set` / `setNull` / `add` / `remove` rules — see
                    `POST /resources/update` for details. An event's required fields —
                    `externalId`, `type` and `eventTime` — reject `setNull` with a 400; `dataSetId`
                    accepts it and detaches the event from its dataset.

                    ### Use sparingly
                    Event updates run a replace-and-cleanup on the stored record. While the
                    update is in flight, a read against the same event can briefly return the
                    pre-update version or a duplicate. Prefer creating a follow-up event that
                    corrects the record rather than mutating the original when it matters for
                    audit.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The events after the update, with current values.",
            content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = EventDataWrapper.class)
    ))
    @ApiResponse(responseCode = "400", description =
            "The request has a problem the server spotted before saving. Typical causes: " +
                    "neither `id` nor `externalId` supplied, the event doesn't exist, or " +
                    "`set` and `setNull` both present on the same field.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description =
            "The new `externalId` already belongs to another event. Pick a different one.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DuplicateError.class)
            ))
    @ApiResponse(responseCode = "429", description = "Too many requests — back off and retry.",
            content = @Content)
    @PostMapping(
            path = "/update",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> update(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UpdateEventDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        {
                                          "externalId": "alarm_pipe_overpressure_2026_04_22_14_30",
                                          "update": {
                                            "status": { "set": "acknowledged" },
                                            "metadata": { "add": { "acked_by": "olav" } }
                                          }
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody
                                    @Schema(implementation = UpdateEventDataWrapper.class)
                                    DataWrapper<UpdateEventForm> apiReqData
    ){
        try{
            DataWrapper<EventModel> results = eventService.update(apiReqData);
            return new ResponseEntity<>(results, HttpStatus.OK);
        } catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            log.error(e.getMessage(), e);
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (org.springframework.security.access.AccessDeniedException e){
            throw e;
        }
        catch (LimitException e){
            // A limit refusal is an answer, not a fault: without this the catch below
            // flattens it into a 500 and the caller never learns which limit they hit.
            throw e;
        }
        catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Delete events",
            description = """
                    Delete one or more events by UUID `id` or `externalId`.

                    ### Cannot be undone
                    Once deleted, an event is gone from queries immediately. Historical
                    reports referencing the event by id will no longer resolve.

                    ### Idempotent
                    Deleting an event that's already gone returns `200` and is a no-op.
                    """
    )
    @ApiResponse(responseCode = "204", description = "The events were deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "400", description = "Malformed request — e.g. neither `id` nor `externalId` supplied on an entry.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @RequestMapping(
            path = "/delete",
            produces = MediaType.APPLICATION_JSON_VALUE,
            method = {RequestMethod.POST, RequestMethod.DELETE}
    )
    public ResponseEntity<?> delete(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UUIDAndExternalIdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "externalId": "alarm_pipe_overpressure_2026_04_22_14_30" }
                                      ]
                                    }
                                    """)
                    )
            )
            @Schema(implementation = UUIDAndExternalIdCollectionDataWrapper.class)
            @RequestBody
            DataWrapper<UUIDAndExternalIdCollection> apiReqData
    ){
        try{
            eventService.delete(apiReqData);
            // 204, like every other delete on the API. This was the lone 200-with-empty-body.
            return ResponseEntity.noContent().build();
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (ResourceDeleteException e){
            log.error(e.getMessage(), e);
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
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

    @Tag(name = "Events")
    @Operation(
            summary = "Count events",
            description = """
                    Return the total number of events in your tenant as `{ "count": N }`.
                    Cheap — runs as a single query. Does not support filters; use
                    `POST /events/filter` with `limit` for filtered counting.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Total event count for your tenant.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EventCountResponse.class),
                    examples = @ExampleObject(value = "{ \"count\": 148392 }")
            ))
    @RequestMapping(
            path = "/count",
            produces = MediaType.APPLICATION_JSON_VALUE,
            method = {RequestMethod.GET}
    )
    public ResponseEntity<?> count(){
        try{
            long count = eventService.count();
            return new ResponseEntity<>(Map.of("count", count), HttpStatus.OK);
        }
        catch (RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Hard cap on how many distinct dimension values a single list/search call may return. */
    private static final int MAX_LIST_LIMIT = 10000;

    @Tag(name = "Events")
    @Operation(
            summary = "List event types",
            description = """
                    Return every distinct `type` value present on events you can read, sorted
                    alphabetically and restricted to the datasets your token grants read access to.
                    `limit` caps the result (default 1000). To substring-match instead of listing all,
                    use `GET /events/search/type`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event types.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @RequestMapping(value = "/list/types", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> listTypes(
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listTypes(null, clampLimit(limit)));
    }

    @Tag(name = "Events")
    @Operation(
            summary = "List event sub-types",
            description = """
                    Return every distinct `subType` value present on events you can read, sorted
                    alphabetically and restricted to your readable datasets. `limit` caps the result
                    (default 1000). For substring matching use `GET /events/search/sub-type`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event sub-types.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @RequestMapping(value = "/list/sub-types", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> listSubTypes(
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listSubTypes(null, clampLimit(limit)));
    }

    @Tag(name = "Events")
    @Operation(
            summary = "List event statuses",
            description = """
                    Return every distinct `status` value present on events you can read, sorted
                    alphabetically and restricted to your readable datasets. `limit` caps the result
                    (default 1000). For substring matching use `GET /events/search/status`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event statuses.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @RequestMapping(value = "/list/statuses", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> listStatuses(
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listStatuses(null, clampLimit(limit)));
    }

    @Tag(name = "Events")
    @Operation(
            summary = "List event sources",
            description = """
                    Return every distinct `source` value present on events you can read, sorted
                    alphabetically and restricted to your readable datasets. `limit` caps the result
                    (default 1000). For substring matching use `GET /events/search/source`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event sources.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @RequestMapping(value = "/list/sources", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> listSources(
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listSources(null, clampLimit(limit)));
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Search event types",
            description = """
                    Return distinct `type` values containing `q` (case-insensitive substring), sorted
                    alphabetically and restricted to your readable datasets — built for type-ahead.
                    `limit` caps the result (default 1000). To list every value, use
                    `GET /events/list/types`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event types matching the query.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Missing required `q` query parameter.")
    @RequestMapping(value = "/search/type", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> searchTypes(
            @Parameter(description = "Case-insensitive substring to match against the values.", example = "alarm") @RequestParam("q") String q,
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listTypes(q, clampLimit(limit)));
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Search event sub-types",
            description = """
                    Return distinct `subType` values containing `q` (case-insensitive substring),
                    sorted alphabetically and restricted to your readable datasets. `limit` caps the
                    result (default 1000). To list every value, use `GET /events/list/sub-types`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event sub-types matching the query.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Missing required `q` query parameter.")
    @RequestMapping(value = "/search/sub-type", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> searchSubTypes(
            @Parameter(description = "Case-insensitive substring to match against the values.", example = "alarm") @RequestParam("q") String q,
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listSubTypes(q, clampLimit(limit)));
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Search event statuses",
            description = """
                    Return distinct `status` values containing `q` (case-insensitive substring), sorted
                    alphabetically and restricted to your readable datasets. `limit` caps the result
                    (default 1000). To list every value, use `GET /events/list/statuses`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event statuses matching the query.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Missing required `q` query parameter.")
    @RequestMapping(value = "/search/status", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> searchStatuses(
            @Parameter(description = "Case-insensitive substring to match against the values.", example = "alarm") @RequestParam("q") String q,
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listStatuses(q, clampLimit(limit)));
    }

    @Tag(name = "Events")
    @Operation(
            summary = "Search event sources",
            description = """
                    Return distinct `source` values containing `q` (case-insensitive substring), sorted
                    alphabetically and restricted to your readable datasets. `limit` caps the result
                    (default 1000). To list every value, use `GET /events/list/sources`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Distinct event sources matching the query.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StringValuesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Missing required `q` query parameter.")
    @RequestMapping(value = "/search/source", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> searchSources(
            @Parameter(description = "Case-insensitive substring to match against the values.", example = "alarm") @RequestParam("q") String q,
            @Parameter(description = "Maximum number of distinct values to return. Capped at 10000.", example = "1000") @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit){
        return listResponse(() -> eventService.listSources(q, clampLimit(limit)));
    }

    private ResponseEntity<?> listResponse(java.util.function.Supplier<DataWrapper<String>> supplier){
        try{
            return new ResponseEntity<>(supplier.get(), HttpStatus.OK);
        } catch (RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private static int clampLimit(int limit){
        return Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
    }

}
