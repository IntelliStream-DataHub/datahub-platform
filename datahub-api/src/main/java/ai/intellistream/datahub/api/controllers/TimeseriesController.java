// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.LimitException;
import ai.intellistream.datahub.api.policy.NamingPolicyViolationException;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.ConflictError;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.api.responses.ValueTypeRecommendation;
import ai.intellistream.datahub.api.responses.swaggerdto.*;
import ai.intellistream.datahub.helpers.timeseries.ValueTypeRecommender;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.TimeseriesRetreiver;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import ai.intellistream.datahub.transformers.TimeseriesTransformer;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/timeseries")
@Slf4j
public class TimeseriesController {

    private final TimeseriesService timeseriesService;
    private final EdgeRepository edgeRepository;
    private final TimeseriesRepository timeseriesRepository;

    public TimeseriesController(
            TimeseriesService timeseriesService,
            EdgeRepository edgeRepository, TimeseriesRepository timeseriesRepository) {
        this.timeseriesService = timeseriesService;
        this.edgeRepository = edgeRepository;
        this.timeseriesRepository = timeseriesRepository;
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "List recent timeseries",
            description = """
                    List timeseries in your tenant, newest first.

                    Use `limit` to cap the response (default 100, max 10000). For targeted
                    lookups, use `POST /timeseries/byids` or `POST /timeseries/search` instead
                    — this endpoint is mainly for browsing.

                    Pass `dataSetId` to list only the timeseries of that dataset **and every
                    dataset beneath it** in the dataset hierarchy — a timeseries attached to a
                    child (or grandchild, …) dataset is included. Datasets you lack read access
                    to are silently omitted.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "1")})
            }
    )
    @ApiResponse(responseCode = "200", description = "Up to `limit` timeseries, newest first.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TimeseriesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "`limit` is not a positive integer ≤ 10000, or `dataSetId` is not a number.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "dataSetId must be a number")
            ))
    @RequestMapping(value = {""},
            method = RequestMethod.GET,
            produces = { "application/json", "application/xml" }
    )
    public ResponseEntity<?> list(@Parameter(description = "Maximum number of timeseries to return. Must be a positive integer up to 10000.", example = "1000") @RequestParam(name="limit", defaultValue = "1000") String limit,
                                  @Parameter(description = "Restrict results to this dataset and every dataset beneath it.", example = "5677892") @RequestParam(name="dataSetId", required = false) String dataSetId){
        int lim;

        if (limit.equalsIgnoreCase("null")) {
            // 1000, the same default POST /timeseries/filter uses. These were 100 and 1000 for the
            // same entity, so which page size you got depended on which endpoint you reached for.
            lim = 1000;
        }
        else {
            try {
                lim = Integer.parseInt(limit);
                if(lim < 0) throw new NumberFormatException("Limit cannot be negative");
                if(lim > 10000) throw new NumberFormatException("Limit cannot be greater than 10000");
            } catch (NumberFormatException e) {
                return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
            }
        }

        Collection<TimeseriesEntity> timeseries;
        if (dataSetId == null || dataSetId.isBlank() || dataSetId.equalsIgnoreCase("null")) {
            timeseries = timeseriesService.readList(lim);
        } else {
            long dataSet;
            try {
                dataSet = Long.parseLong(dataSetId);
            } catch (NumberFormatException e) {
                return new ResponseEntity<>("dataSetId must be a number", HttpStatus.BAD_REQUEST);
            }
            timeseries = timeseriesService.readListForDataSet(dataSet, lim);
        }
        List<Long> nodeIds = timeseries.stream().map(TimeseriesEntity::getId).collect(Collectors.toList());
        Collection<EdgeEntity> edgeEntities = edgeRepository.findAllByEndIn(nodeIds, EdgeEntity.class);
        Collection<Timeseries> tsList = TimeseriesTransformer.from(timeseries, edgeEntities);
        var data = new DataWrapper<Timeseries>();
        data.setItems(tsList);
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @Hidden
    @RequestMapping(value = {"/"}, method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> listWithSlash(@Parameter(description = "Maximum number of timeseries to return. Must be a positive integer up to 10000.", example = "100") @RequestParam(name="limit", defaultValue = "100") String limit,
                                           @Parameter(description = "Restrict results to this dataset and every dataset beneath it.", example = "5677892") @RequestParam(name="dataSetId", required = false) String dataSetId){
        return list(limit, dataSetId);
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Get a timeseries by id",
            description = """
                    Look up one timeseries by its numeric `id`.

                    A timeseries you may not read is reported as **404**, not 403 — a hidden
                    timeseries must be indistinguishable from a missing one.

                    To look one up by `externalId`, or several at once, use `POST /timeseries/byids`.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "1")})
            }
    )
    @ApiResponse(responseCode = "200", description = "The timeseries was found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TimeseriesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "404", description =
            "No timeseries with this `id` exists, or you may not read the data set it belongs to.",
            content = @Content(mediaType = "application/problem+json"))
    @GetMapping(value = "/{id}", produces = { "application/json", "application/xml" })
    public ResponseEntity<?> get(
            @Parameter(description = "Numeric id of the timeseries.", example = "5677892")
            @PathVariable("id") Long id){
        // Not-found and no-read-access both surface as ObjectNotFoundException, mapped to 404 by
        // ObjectNotFoundExceptionHandler — the same rule GET /resources/{id} follows.
        return new ResponseEntity<>(timeseriesService.get(id), HttpStatus.OK);
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Recommend a value type for a unit",
            description = """
                    Suggest the timeseries `valueType` that gives the best ClickHouse compression for a
                    unit of measure, while still representing the data faithfully. Identify the unit by
                    its catalogue `externalId` (e.g. `temperature_deg_c`, `pressure_bar`, `pressure_pa`).

                    The mapping is heuristic and hard-coded — small low-precision ranges map to
                    `DECIMAL32` (exact, 4 bytes, best ratio), wide-magnitude analog values to `FLOAT32`
                    (4 bytes, full range). An unknown unit returns a compact default with
                    `recognized=false`. Use the result to pre-select the value type when creating a
                    timeseries — it is advice, not a constraint.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "1")})
            }
    )
    @ApiResponse(responseCode = "200", description = "The recommended value type for the unit.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ValueTypeRecommendation.class)
            ))
    @GetMapping(
            value = "/recommend-value-type/{unitExternalId}",
            produces = { "application/json", "application/xml" }
    )
    public ResponseEntity<ValueTypeRecommendation> recommendValueType(
            @Parameter(description = "External id of the unit to recommend a value type for.", example = "temperature:deg_c") @PathVariable("unitExternalId") String unitExternalId){
        return new ResponseEntity<>(ValueTypeRecommender.recommend(unitExternalId), HttpStatus.OK);
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Find timeseries by id or externalId",
            description = """
                    Look up several timeseries in one call. Each entry in `items[]` carries
                    either a numeric `id`, an `externalId`, or both — mix freely.

                    Timeseries that don't exist are silently omitted from the response; compare
                    the returned items against what you asked for to detect missing ones.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "2")})
            }
    )
    @ApiResponse(responseCode = "200", description = "The timeseries that were found. Missing ones are silently left out.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TimeseriesDataWrapper.class)
            ))
    @PostMapping(
            path = "/byids",
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public DataWrapper<Timeseries> findByIdList(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Identifiers of the timeseries to look up.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "id": 5677892 },
                                        { "externalId": "sensor_temp_room_a" }
                                      ]
                                    }
                                    """)
                    )
            )
            @Schema(implementation = IdCollectionDataWrapper.class)
            @Valid @RequestBody DataWrapper<IdCollection> apiReqData
    ){
        return timeseriesService.byids(apiReqData.getItems());
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "List timeseries matching filter criteria",
            description = """
                    Return the timeseries that match a set of filters. All filters are combined
                    with AND — a timeseries must match every filter you supply to be included.

                    Every list field also accepts a bare value, so `"unit": "bar"` and
                    `"unit": ["bar"]` mean the same thing.

                    Supported filters:
                    - `dataSetId` — timeseries of those datasets **and every dataset beneath
                      them** in the dataset hierarchy. Each entry names a dataset by `id` or
                      `externalId`.
                    - `id` / `externalId` / `name` / `source` / `labels` — the criteria every
                      node type shares. `externalId`, `name` and `source` are pattern lists:
                      `*` and `%` are wildcards, `_` is literal, matching is case-insensitive, and
                      an entry without a wildcard matches exactly. `labels` must **all** be present.
                    - `unit` / `unitExternalId` — pattern lists on the same rules.
                    - `valueType` — `BIGINT`, `FLOAT`, `FLOAT32`, `NUMERIC`, `DECIMAL32`, `TEXT`
                      or `MIXED`. Matched exactly (it is a closed catalogue), case-insensitively.
                    - `metadata` — every entry must be present. A **null value matches the key
                      alone**, whatever it carries, so `{"health": null}` finds anything tagged
                      `health`. This replaced the old `metadataKey`/`metadataValue` pair.
                    - `createdTime` / `lastUpdatedTime` — inclusive `min`/`max` instants.

                    Datasets you lack read access to are silently omitted. Use `limit` to cap the
                    result size (default 1000, max 10000); results come newest created first. For
                    free-text lookups use `POST /timeseries/search` instead.

                    `sort` takes one property — `id`, `externalId`, `name`, `source`,
                    `description`, `createdTime`, `lastUpdatedTime` or `dataSetId` — with `order` of
                    `asc` or `desc`; `id` is always appended so the order is total. The response
                    carries `nextCursor` when there may be more: send it back as `cursor`, with the
                    same `sort` it came from, and keep going while it is present. Keyset paging, not
                    `OFFSET`, so a deep page costs what a shallow one does.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "2")})
            }
    )
    @ApiResponse(responseCode = "200",
            description = "The timeseries that match every supplied filter, newest first. Empty `items[]` means nothing matched.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TimeseriesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "The request failed validation, e.g. `limit` above 10000 or an over-long filter value.")
    @PostMapping(
            path = "/filter",
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
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
                                        "dataSetId": [{ "id": "12" }],
                                        "name": ["RPM*"],
                                        "externalId": ["rpm_pump_*"],
                                        "labels": ["PUMP"],
                                        "unit": ["bar"],
                                        "valueType": ["FLOAT"],
                                        "metadata": { "sensor_vendor": null }
                                      }
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody TimeseriesRetreiver apiReqData){
        return new ResponseEntity<>(timeseriesService.filter(apiReqData), HttpStatus.OK);
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Create timeseries",
            description = """
                    Create one or more **timeseries** — containers that hold a sequence of
                    timestamped values (temperature readings, flow rates, status codes…).

                    Each timeseries needs:
                    - a unique `externalId` within your tenant,
                    - a `name`,
                    - a `valueType` — `BIGINT`, `FLOAT`, `FLOAT32`, `NUMERIC`, `DECIMAL32`,
                      `TEXT`, or `MIXED` — which fixes what kinds of values you can write to it later,
                    - optional `unit` / `unitExternalId` if you're tracking a physical quantity,
                    - optional `dataSetId` to group it with related data.

                    ### All-or-nothing
                    If any timeseries in the request fails validation, none are created.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "3")})
            }
    )
    @ApiResponse(responseCode = "201", description = "The created timeseries, with server-assigned `id`s you can use in later calls.",
        content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = TimeseriesDataWrapper.class)
        ))
    @ApiResponse(responseCode = "400", description =
            "The request has a problem the server spotted before saving. Typical causes: " +
                    "missing required field, invalid `valueType`, referenced `dataSetId` " +
                    "doesn't exist, unknown `unit`.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description =
            "A timeseries with one of the `externalId`s already exists. The `duplicated` " +
                    "list tells you which ones. Pick a different `externalId`, or use " +
                    "`POST /timeseries/update` to modify the existing timeseries.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DuplicateError.class)
            ))
    @ApiResponse(responseCode = "422", description =
            "One or more fields failed validation rules. Response lists the offending fields.",
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
                    description = "Timeseries to create.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TimeseriesDataWrapper.class),
                            examples = @ExampleObject(
                                    name = "Temperature sensor",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "externalId": "sensor_temp_room_a",
                                                  "name": "Room A temperature",
                                                  "description": "Ambient temp, pump room A",
                                                  "valueType": "FLOAT",
                                                  "unit": "celsius",
                                                  "dataSetId": 12,
                                                  "labels": ["SENSOR"],
                                                  "metadata": { "location": "rack-12" }
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody
                                        @Schema(implementation = TimeseriesDataWrapper.class)
                                        DataWrapper<Timeseries> apiReqData
    ){
        try{
            apiReqData = timeseriesService.save(apiReqData);
            return new ResponseEntity<>(apiReqData, HttpStatus.CREATED);
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (NamingPolicyViolationException e){
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response; the
            // BadRequestException catch below would flatten it and lose the per-item violations.
            throw e;
        } catch (BadRequestException e){
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (AccessDeniedException e){
            throw e;
        }
        catch (LimitException e){
            // A limit refusal is an answer, not a fault: without this the catch below
            // flattens it into a 500 and the caller never learns which limit they hit.
            throw e;
        }
        catch (RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Update timeseries",
            description = """
                    Change fields on existing timeseries. Identify each one by `id` or
                    `externalId`. Only fields you name in the `update` block are changed.

                    Each updatable field is an object:
                    - `"set"` — replace the field with this value.
                    - `"setNull": true` — clear the field. Rejected with a 400 on `name` and
                      `externalId`, which every timeseries must have; use `set` to change them.
                    - `"add"` / `"remove"` — for `metadata`, add or remove specific keys.

                    ### Value type is fixed
                    `valueType` cannot be changed after creation — it determines how stored
                    data-points were encoded. To change type, create a new timeseries.

                    ### All-or-nothing
                    A single validation failure rolls back the whole batch.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "4")})
            }
    )
    @ApiResponse(responseCode = "200", description = "The timeseries after the update, with current values.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TimeseriesDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description =
            "The request has a problem the server spotted before saving. Typical causes: " +
                    "neither `id` nor `externalId` supplied, the timeseries doesn't exist, or " +
                    "`set` and `setNull` both present on the same field.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description =
            "Conflict. Either the new `externalId` already belongs to another timeseries " +
                    "(`DuplicateError` — pick a different one), or someone else changed the " +
                    "timeseries while your update was in flight (`ConflictError` — re-fetch " +
                    "with `/byids` and retry).",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(oneOf = {DuplicateError.class, ConflictError.class})
            ))
    @PostMapping(
            path = "/update",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> update(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Timeseries to update. Only fields you name in `update` are changed.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UpdateTimeseriesWrapper.class),
                            examples = @ExampleObject(
                                    name = "Rename and add metadata",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "externalId": "sensor_temp_room_a",
                                                  "update": {
                                                    "name": { "set": "Room A ambient temp" },
                                                    "description": { "set": "Primary ambient sensor, verified" },
                                                    "metadata": { "add": { "calibration_due": "2026-10-01" } }
                                                  }
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @Schema(implementation = UpdateTimeseriesWrapper.class)
            @RequestBody DataWrapper<UpdateTimeseries> apiReqData
    ){
        try{
            DataWrapper<Timeseries> data = timeseriesService.updateTimeseries(apiReqData);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (NamingPolicyViolationException e){
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response; the
            // BadRequestException catch below would flatten it and lose the per-item violations.
            throw e;
        } catch (BadRequestException e){
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (AccessDeniedException e){
            throw e;
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler — the broad
        // RuntimeException catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (LimitException e){
            // A limit refusal is an answer, not a fault: without this the catch below
            // flattens it into a 500 and the caller never learns which limit they hit.
            throw e;
        } catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Full-text search timeseries",
            description = """
                    Free-text search across timeseries. The phrase is matched against `name`,
                    `externalId` and `description`. Matching is fuzzy and word-aware: search for
                    `temp` and you'll also find `temperature`, `tempered`, and multi-word names
                    containing the term.

                    ### Narrowing the results
                    `filter` is optional and takes the same criteria as `POST /timeseries/filter`
                    — `unit`, `valueType`, `dataSetId`, metadata, and the rest. It only ever
                    *removes* matches; the phrase decides what the candidates are. Omit it for no
                    narrowing.

                    If you don't need a phrase at all, use `POST /timeseries/filter`: a structured
                    query on its own is faster and more predictable than one bolted to a search.

                    `limit` caps the result size (default 100, max 1000).

                    ### Result order
                    Ranked by relevance (`ts_rank`), strongest match first, with `id` as a
                    tie-break so equal-scoring rows keep a stable order and repeated identical
                    requests agree. Ranking means the database scores and sorts every match before
                    applying `limit`, so a very broad phrase costs more than a narrow one.

                    Matching rules may evolve over time; don't rely on this endpoint for equality
                    tests — use `POST /timeseries/byids` for that.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "5")})
            }
    )
    @ApiResponse(responseCode = "200", description = "Timeseries ranked by how well they match the search phrase.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TimeseriesDataWrapper.class)
            ))
    @PostMapping(
            path = "/search",
            produces = {"application/json"},
            consumes = {"application/json"}
    )
    public ResponseEntity<?> search(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Search phrase, optional filter, optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {
                                      "search": { "query": "temperature" },
                                      "filter": { "unit": "deg_c" },
                                      "limit": 50
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody SearchBody<TimeseriesFilter> apiReqData){
        try {
            return ResponseEntity.ok(timeseriesService.search(apiReqData));
        } catch (ConstraintViolationException cve) {
            // Body validation is @Valid's job and never reaches this catch; it stays for a
            // violation raised deeper in the service, which would otherwise surface as an
            // unshaped Spring 500.
            return new ResponseEntity<>(BuildErrorResponse.createConstraintViolationError(cve), HttpStatus.BAD_REQUEST);
        }
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Delete timeseries",
            description = """
                    Delete one or more timeseries by `id` or `externalId`. Deletes the
                    timeseries definition **and every data-point it contains** — this cannot
                    be undone.

                    The definition is gone the moment this call returns; the data-point purge is
                    handed off asynchronously and completes shortly after. The points are already
                    unreachable in the meantime, since every read resolves the timeseries first.

                    ### Safety checks
                    The request is rejected with `400 Bad Request` if:
                    - any targeted timeseries is the **start** of a relationship — delete those
                      relationships first via `POST /edges/delete`;
                    - any targeted timeseries is still bound to one or more **subscriptions** —
                      the response body lists the blocking subscriptions
                      (`subscriptionId`, `subscriptionExternalId`, `timeseriesId`). Remove them
                      via `POST /subscriptions/delete` first, then retry.

                    ### Idempotent
                    Deleting a timeseries that's already gone returns `204` and is a no-op.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "6")})
            }
    )
    @ApiResponse(responseCode = "204", description = "The timeseries were deleted and their data-points scheduled for purge. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "400",
            description =
                    "A safety check failed. Either the timeseries is still linked to other " +
                    "resources via a relationship, or it's still bound to a subscription. " +
                    "Response lists the blocking items so you can clean them up first.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 400,
                                "message": "Cannot delete timeseries that are referenced by subscription(s). Remove the subscriptions first.",
                                "fields": [
                                  {
                                    "type": "subscription",
                                    "subscriptionId": "91",
                                    "subscriptionExternalId": "fleet_dashboard",
                                    "timeseriesId": "5677892"
                                  }
                                ]
                              }
                            }
                            """)
            ))
    @ApiResponse(responseCode = "409", description =
            "Concurrency conflict — another request modified or deleted the timeseries " +
                    "between read and write. Clients should re-fetch the current state and retry.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class)
            ))
    @RequestMapping(
            path = "/delete",
            produces = {"application/json"},
            consumes = {"application/json"},
            method = { RequestMethod.DELETE, RequestMethod.POST }
    )
    public ResponseEntity<?> delete(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Identifiers of the timeseries to delete.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "externalId": "sensor_temp_room_a" }
                                      ]
                                    }
                                    """)
                    )
            )
            @Schema(implementation = IdCollectionDataWrapper.class)
            @Valid @RequestBody DataWrapper<IdCollection> apiReqData
    ){
        try{
            timeseriesService.deleteTimeseries(apiReqData);
        } catch (AccessDeniedException e){
            throw e;
        } catch (NamingPolicyViolationException e){
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response; the
            // BadRequestException catch below would flatten it and lose the per-item violations.
            throw e;
        } catch (BadRequestException e){
            log.warn("Timeseries delete bad request: {}", e.getError().getError().getMessage());
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // A timeseries still referenced by a subscription can't be deleted; the shared resource
        // delete pipeline throws ResourceDeleteException listing the blocking subscription(s).
        // Map it to a 400 with that body (as ResourceController/EventController do) instead of
        // letting it fall through to the bare 500 below.
        catch (ResourceDeleteException e){
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler — the broad
        // Exception catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (Exception e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.noContent().build();
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Insert data-points",
            description = """
                    Append timestamped values to one or more timeseries.

                    Group data-points by target timeseries: each entry in `items[]` names a
                    timeseries (by `id` or `externalId`) and carries its list of `datapoints`.
                    `timestamp` is epoch milliseconds (UTC). `value` must match the target
                    timeseries' `valueType`:
                    - `BIGINT` — a 64-bit integer as a string ("42").
                    - `FLOAT` / `NUMERIC` — a number as a string ("3.14").
                    - `TEXT` — any string.

                    ### Overwrites are deterministic
                    If you insert a data-point whose timestamp already exists, the new value
                    replaces the old one for that timeseries+timestamp. This is intentional —
                    idempotent retries are safe.

                    ### Some targets missing
                    If every targeted timeseries exists, the call returns `204 No Content` with
                    an empty body. If some don't exist, the data-points for the ones that *do*
                    exist are still inserted and the call returns `404 Not Found`, with the
                    missing timeseries listed as per-entry errors in the response body.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "7")})
            }
    )
    @ApiResponse(responseCode = "204", description = "All targeted timeseries existed and their data-points were accepted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "404", description =
            "One or more targeted timeseries don't exist. The data-points for the timeseries " +
                    "that *do* exist are still inserted; the response body lists the missing ones " +
                    "as per-entry errors, each carrying the offending `externalId`/`id`.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "422", description =
            "A value failed to parse against the target timeseries' `valueType` — e.g. " +
                    "text value sent to a `BIGINT` timeseries. Fix the offending entry and retry."
    )
    @PostMapping( path = "/data",
            produces = {"application/json"},
            consumes = {"application/json"}
    )
    public ResponseEntity<?> insertDataPoints(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Data-points grouped by target timeseries.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DatapointsCollectionDataWrapper.class),
                            examples = @ExampleObject(
                                    name = "Two readings for one temperature sensor",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "externalId": "sensor_temp_room_a",
                                                  "datapoints": [
                                                    { "timestamp": 1745328000000, "value": "22.4" },
                                                    { "timestamp": 1745328060000, "value": "22.6" }
                                                  ]
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @Schema(implementation = DatapointsCollectionDataWrapper.class)
            @RequestBody @Valid
            DataWrapper<DatapointsCollection> apiReqData
    ){
        try{
            DataWrapper<?> data = timeseriesService.insertDatapoints(apiReqData);
            if(data.getItems() != null && !data.getItems().isEmpty()){
                // Some targeted timeseries didn't exist. Their data-points were skipped while the
                // rest were inserted — report the misses with 404 and the per-entry error body.
                return new ResponseEntity<>(data, HttpStatus.NOT_FOUND);
            }
        } catch (AccessDeniedException | LimitException e){
            // A limit refusal is an answer, not a fault: let it reach its advice, which turns it
            // into the 429 or 403 that says which limit and how it clears.
            throw e;
        } catch (Exception e){
            log.error(e.getMessage(), e);
            return ResponseEntity.unprocessableContent().body(e.getMessage());
        }
        // Every targeted timeseries existed and its data-points were accepted — no content to return.
        return ResponseEntity.noContent().build();
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Retrieve data-points",
            description = """
                    Fetch data-points from one or more timeseries, optionally filtered by
                    time range, limit, aggregation, or granularity.

                    Typical use:
                    - Name the target timeseries by `id` or `externalId`.
                    - Pick a time window with `start` / `end` (epoch milliseconds). Leave them
                      out to get the most recent `limit` points.
                    - Pass an `aggregates` list (`AVG`, `MIN`, `MAX`, `COUNT`, `SUM`) and a
                      `granularity` (`1m`, `1h`, `1d`) to get bucketed summaries instead of raw
                      points — dramatically smaller responses for long windows.

                    ### Cursors for large windows
                    If the server decides the result is too big to return in one response, it
                    streams it via a `cursor`. The response carries a `nextCursor` string; send
                    it back in the next request to get the following page. `nextCursor` is
                    absent when there's no more data.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "8")})
            }
    )
    @ApiResponse(responseCode = "200", description = "Data-points per requested timeseries. Entries may include `nextCursor` for paginated windows.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DatapointsDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description =
            "Invalid filter — e.g. `granularity` set without `aggregates`, or `start` > `end`."
    )
    @PostMapping( path = "/data/list",
            produces = {"application/json"},
            consumes = {"application/json"}
    )
    public ResponseEntity<?> retrieveDatapoints(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Per-timeseries retrieval filters.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DataRetrieverForDatapoints.class),
                            examples = @ExampleObject(
                                    name = "Hourly averages for last 24 hours",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "externalId": "sensor_temp_room_a",
                                                  "start": 1745241600000,
                                                  "end": 1745328000000,
                                                  "aggregates": ["AVG", "MAX"],
                                                  "granularity": "1h",
                                                  "limit": 24
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody
            @Schema(implementation = DataRetrieverForDatapoints.class)
            DataRetriever<RetrieveFilter> apiReqData
    ){
        try{
            DataWrapper<?> data = timeseriesService.retrieveDatapointsFromCH(apiReqData);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (NamingPolicyViolationException e){
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response; the
            // BadRequestException catch below would flatten it and lose the per-item violations.
            throw e;
        } catch (BadRequestException e){
            log.error(e.getMessage());
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Delete data-points",
            description = """
                    Remove data-points from one or more timeseries by time range. The
                    timeseries definition itself is left intact — use `POST /timeseries/delete`
                    to remove that.

                    Identify each target timeseries by `id` or `externalId`, then give the
                    window to clear as `inclusiveBegin` / `exclusiveEnd`. Both take either
                    ISO-8601 (`2026-01-01T00:00:00Z`) or epoch milliseconds, and both are
                    optional: leave `exclusiveEnd` out to delete everything from
                    `inclusiveBegin` onward, or `inclusiveBegin` out to delete everything
                    up to `exclusiveEnd`. Leave **both** out and the whole series is cleared,
                    which is how you empty a series without losing its definition, edges and
                    subscriptions.

                    ### Cannot be undone
                    Deleted data-points are gone. Double-check the window before calling this.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "9")})
            }
    )
    @ApiResponse(responseCode = "204", description = "The data-points in the requested windows were removed. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "400", description =
            "A named timeseries does not exist, or a window bound is neither ISO-8601 nor epoch milliseconds.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)))
    @ApiResponse(responseCode = "500", description =
            "The delete couldn't be accepted right now. Safe to retry after a short backoff.",
            content = @Content)
    @RequestMapping(
            path = "/data/delete",
            produces = {"application/json"},
            consumes = {"application/json"},
            method = { RequestMethod.DELETE, RequestMethod.POST }
    )
    public ResponseEntity<?> deleteDatapoints(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Per-timeseries delete windows.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DeleteDatapointCollection.class),
                            examples = @ExampleObject(
                                    name = "Delete one day of readings",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "externalId": "sensor_temp_room_a",
                                                  "inclusiveBegin": "1745241600000",
                                                  "exclusiveEnd": "1745328000000"
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @Schema(implementation = DeleteDatapointCollection.class)
            @RequestBody DataRetriever<DeleteDatapoint> apiReqData
    ) {
        try{
            timeseriesService.deleteDatapoints(apiReqData);
            return new ResponseEntity<>("", HttpStatus.NO_CONTENT);
        } catch (PulsarClientException e){
            log.error(e.getMessage(), e);
        }
        return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Tag(name = "Time-series")
    @Operation(
            summary = "Retrieve the latest data-point per timeseries",
            description = """
                    For each timeseries you name, return its single most recent data-point
                    (the one with the highest `timestamp`). Cheap and fast — ideal for
                    dashboards that show "current value" widgets.

                    If a timeseries has no data-points at all, it's omitted from the response.
                    """,
            extensions = {
                    @Extension(properties = {@ExtensionProperty(name = "x-sort", value = "10")})
            }
    )
    @ApiResponse(responseCode = "200", description = "The latest data-point per requested timeseries. Empty timeseries are omitted.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DataWrapperCollectionDatapoint.class)
    ))
    @PostMapping(
            path = "/data/latest",
            produces = {MediaType.APPLICATION_JSON_VALUE},
            consumes = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<?> latestDatapoint(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Identifiers of the timeseries to fetch the latest point from.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "externalId": "sensor_temp_room_a" },
                                        { "externalId": "sensor_flow_main" }
                                      ]
                                    }
                                    """)
                    )
            )
            @Schema(implementation = IdCollectionDataWrapper.class)
            @RequestBody
            DataWrapper<IdCollection> apiReqData
    ){
        try{
            var data = timeseriesService.fetchLatestDatapoint(apiReqData);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (AccessDeniedException e){
            throw e;
        } catch (Exception e){
            log.error(e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
