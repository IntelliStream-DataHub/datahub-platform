// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.policy.NamingPolicyViolationException;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.api.controllers.errors.ConflictError;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.DataSetDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.DataSetFormDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.PolicyDataWrapper;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.services.DataSetService;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.forms.DataSetForm;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import ai.intellistream.datahub.transformers.DataSetTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/datasets")
@Slf4j
@Tag(name = "Data sets", description = "Data sets group and track data by its source. For example, a data set can contain all work orders originating from SAP. Typically, an organization will have one data set for each of its data ingestion pipelines in DataHub")
public class DataSetController {

    private final DataSetService dataSetService;
    private final ResourceService resourceService;
    private final DataSecurity dataSecurity;
    private final Validator validator;
    private final ai.intellistream.datahub.repositories.node.PolicyRepository policyEntityRepository;
    private final DataSetRepository dataSetRepository;

    public DataSetController(
            DataSetService dataSetService,
            ResourceService resourceService,
            DataSecurity dataSecurity,
            Validator validator,
            DataSetRepository dataSetRepository,
            ai.intellistream.datahub.repositories.node.PolicyRepository policyEntityRepository) {
        this.dataSetService = dataSetService;
        this.dataSecurity = dataSecurity;
        this.resourceService = resourceService;
        this.validator = validator;
        this.dataSetRepository = dataSetRepository;
        this.policyEntityRepository = policyEntityRepository;
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "Get a data set by id",
            description = """
                    Look up one data set by its numeric `id`.

                    To look one up by `externalId`, or several at once, use `POST /datasets/byids`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The data set was found.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DataSetDataWrapper.class)
    ))
    @ApiResponse(responseCode = "404", description = "No data set with this `id` exists.",
            content = @Content(mediaType = "application/problem+json"))
    @GetMapping(value = "/{id}", produces = { "application/json", "application/xml" })
    public ResponseEntity<?> get(
            @Parameter(description = "Numeric id of the data set.", example = "5677892")
            @PathVariable("id") Long id){
        // A missing data set throws ObjectNotFoundException → 404 via ObjectNotFoundExceptionHandler.
        return new ResponseEntity<>(dataSetService.get(id), HttpStatus.OK);
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "Find datasets by id or externalId",
            description = """
                    Look up several datasets in one call. Each entry in `items[]` carries
                    either a numeric `id`, an `externalId`, or both.

                    Datasets that don't exist are silently omitted. Compare the returned items
                    against what you asked for to detect missing ones.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The datasets that were found. Missing ones are silently left out.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DataSetDataWrapper.class)
    ))
    @RequestMapping(value = "/byids", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> byIds(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "externalId": "sap_work_orders" },
                                        { "id": 12 }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody
                                   @Schema(implementation = IdCollectionDataWrapper.class)
                                   DataWrapper<IdCollection> form
    ){
        try{
            DataWrapper<DataSetModel> data = new DataWrapper<>();
            List<DatasetEntity> dataSetNodes = dataSetRepository.findAllByIdCollection(form.getItems());
            Collection<DataSetModel> results = DataSetTransformer.toDataSetModel(ResourceTransformer.from(dataSetNodes));
            data.setItems(results);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (Exception e){
            log.error(e.getMessage(), e);
        }
        return new ResponseEntity<>("Internal programming error.", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "List all datasets",
            description = """
                    List the datasets in your tenant, newest first.

                    Datasets are a small, slow-changing set per tenant (typically one per
                    ingestion pipeline), so listing them all is cheap — send an empty body
                    (`{}`) and you get the lot, up to `limit`.

                    This takes the same body as `POST /datasets/filter` and behaves identically;
                    `/filter` is the name the resource, timeseries and event endpoints use for
                    the same operation.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The datasets in your tenant, capped at `limit`.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DataSetDataWrapper.class)
    ))
    @ApiResponse(responseCode = "400", description = "The request failed validation — typically a `limit` above 10000.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "limit: must be less than or equal to 10000")
            ))
    @RequestMapping(value = {"/list"}, method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> list(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Optional filter criteria and limit. An empty object lists everything.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DataSetRetreiver.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "limit": 100
                                    }
                                    """)
                    )
            )
            @RequestBody
            @Schema(implementation = DataSetRetreiver.class)
            DataSetRetreiver form
    ){
        return filter(form);
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "Filter datasets",
            description = """
                    Structured filtering over datasets. Every criterion is optional and they
                    AND together, so an empty `filter` returns every dataset — the same thing
                    `POST /datasets/list` does.

                    * `id` — datasets named directly by id. An empty list places no restriction.
                    * `externalId` / `name` / `source` — pattern lists, OR-ed within each list.
                      `*` and `%` are both wildcards, so `["sap_work_orders", "plant_*"]` mixes an
                      exact id with a prefix search, and `["*_archive"]` is a suffix one. An entry
                      with no wildcard matches exactly. `_` is literal — external ids are built out
                      of underscores, so it has to be. All three match case-insensitively.
                    * `labels` — datasets carrying **all** of these labels. Names are canonicalised,
                      so `pump a` finds the label stored as `PUMP_A`.
                    * `metadata` — every key/value pair given must be present on the dataset.
                    * `createdTime` / `lastUpdatedTime` — inclusive `min`/`max` instants.

                    For free-text matching over name and description use
                    `POST /datasets/search` instead.

                    Results come newest created first, capped by `limit` (default 1000, max 10000).

                    `sort` takes one property — `id`, `externalId`, `name`, `source`,
                    `description`, `createdTime`, `lastUpdatedTime` or `dataSetId` — with `order` of
                    `asc` or `desc`; `id` is always appended so the order is total. The response
                    carries `nextCursor` when there may be more: send it back as `cursor`, with the
                    same `sort` it came from, and keep going while it is present. Keyset paging, not
                    `OFFSET`, so a deep page costs what a shallow one does.
                    """
    )
    @ApiResponse(responseCode = "200",
            description = "The datasets matching every supplied criterion. Empty `items[]` means nothing matched.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DataSetDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "The request failed validation — typically a `limit` above 10000.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "limit: must be less than or equal to 10000")
            ))
    @RequestMapping(value = {"/filter"}, method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> filter(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Filter criteria and optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DataSetRetreiver.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "limit": 100,
                                      "filter": {
                                        "name": ["SAP*"],
                                        "externalId": ["sap_*"],
                                        "source": ["sap", "opc_*"],
                                        "metadata": { "owner": "plant-a" },
                                        "createdTime": {
                                          "min": "2026-01-01T00:00:00Z"
                                        }
                                      }
                                    }
                                    """)
                    )
            )
            @RequestBody
            @Schema(implementation = DataSetRetreiver.class)
            DataSetRetreiver form
    ){
        try{
            Set<ConstraintViolation<DataSetRetreiver>> errors = validator.validate(form);
            if (!errors.isEmpty()) {
                throw new ConstraintViolationException(errors);
            }
            return new ResponseEntity<>(dataSetService.filter(form), HttpStatus.OK);
        } catch (BadRequestException | MalformedCursorException e){
            // Let these reach their advices. The catch-all below would otherwise report a caller
            // mistake — a malformed cursor, say — as "Internal programming error." with a 500,
            // which blames the server for something the request got wrong.
            throw e;
        } catch (ConstraintViolationException e){
            log.error(e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e){
            log.error(e.getMessage(), e);
        }
        return new ResponseEntity<>("Internal programming error.", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "Create datasets",
            description = """
                    Create one or more **datasets**. A dataset is a container that groups
                    resources and timeseries by their origin — typically one dataset per
                    ingestion pipeline ("SAP work orders", "Plant A telemetry", etc.).

                    Each dataset needs a unique `externalId` within your tenant, a `name`, and
                    optionally a `description`. Once created, refer to it from resources and
                    timeseries via their `dataSetId` field to group them.
                    """
    )
    @ApiResponse(responseCode = "201", description = "The created datasets with server-assigned `id`s.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DataSetDataWrapper.class)
    ))
    @ApiResponse(responseCode = "400", description = "The request has a problem the server spotted before saving. The `fields` list tells you which input was wrong.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description = "A dataset with one of the `externalId`s already exists. Pick a different one, or use `POST /datasets/update`.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DuplicateError.class)
            ))
    @RequestMapping(value = { "/create"},
            method = RequestMethod.POST,
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Datasets to create.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DataSetDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        {
                                          "externalId": "sap_work_orders",
                                          "name": "SAP Work Orders",
                                          "description": "Work orders mirrored from SAP every 15 minutes"
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody
                                    @Schema(implementation = DataSetDataWrapper.class)
                                    DataWrapper<DataSetModel> form
    ){
        // A data set is the unit access is granted on, so managing one is an operator action:
        // creating, renaming or re-parenting it changes what existing grants cover.
        // Requires an all-datasets write grant. See DATASET_ACL_SETUP.md.
        dataSecurity.assertCanManageDataSets();
        try{
            Collection<DataSetModel> dataSets = form.getItems();

            // We need a collection of policy nodes when creating new data sets.
            List<PolicyEntity> policies = policyEntityRepository.findAll();
            Set<Long> dataSetIds = dataSets.stream()
                    .map(DataSetModel::getConnectedDataSets)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
            List<IdCollection> connectedDataSets = dataSetRepository.findAllByIdIn(dataSetIds, IdCollection.class);

            GraphDataWrapper<NodeModel, RelForm> newDataSets =
                    DataSetTransformer.toGraphForm(dataSets, policies, connectedDataSets);
            var results = resourceService.create(newDataSets);

            DataWrapper<DataSetModel> data = new DataWrapper<>();
            Collection<DataSetModel> savedDataSets = DataSetTransformer.toDataSetModel(results.getNodes());
            data.setItems(savedDataSets);
            // The naming policy runs inside the shared create path; its warnings have to travel
            // out with the response, or the caller is told nothing about a name it should fix.
            data.setWarnings(results.getWarnings());
            return new ResponseEntity<>(data, HttpStatus.CREATED);
        } catch (PulsarClientException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (NamingPolicyViolationException e) {
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response. The
            // BadRequestException catch below would otherwise flatten it into the generic error
            // envelope and lose the per-item `violations` list, which is the useful part.
            throw e;
        } catch (BadRequestException e) {
            // Return the clean error envelope, not the exception itself — serializing
            // the Throwable leaks a full stack trace to the client and buries the
            // message a level deeper than clients expect (mirrors the DuplicateData
            // handling just below).
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        }
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "Update datasets",
            description = """
                    Change fields on existing datasets. Identify each one by `id` or
                    `externalId`. Only fields you name in the `update` block are changed.

                    The same `set` / `setNull` / `add` / `remove` rules apply as for resources
                    — see `POST /resources/update` for details, including the 400 on `setNull`
                    against `name` and `externalId`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The datasets after the update, with current values.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DataSetDataWrapper.class)
    ))
    @ApiResponse(responseCode = "400", description = "Dataset not found or update rules malformed.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description = "The new `externalId` already belongs to another dataset.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DuplicateError.class)
            ))
    @RequestMapping(value = { "/update"},
            method = RequestMethod.POST,
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> update(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DataSetFormDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        {
                                          "externalId": "sap_work_orders",
                                          "update": {
                                            "description": { "set": "SAP work orders — live sync" }
                                          }
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody
                                    @Schema(implementation = DataSetFormDataWrapper.class)
                                    DataWrapper<DataSetForm> form
    ){
        // A data set is the unit access is granted on, so managing one is an operator action:
        // creating, renaming or re-parenting it changes what existing grants cover.
        // Requires an all-datasets write grant. See DATASET_ACL_SETUP.md.
        dataSecurity.assertCanManageDataSets();
        try{
            DataWrapper<DataSetModel> data = dataSetService.update(form);
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (PulsarClientException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        } catch (ConstraintViolationException cve){
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (NamingPolicyViolationException e) {
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response. The
            // BadRequestException catch below would otherwise flatten it into the generic error
            // envelope and lose the per-item `violations` list, which is the useful part.
            throw e;
        } catch (BadRequestException e) {
            // Return the clean error envelope, not the exception itself — serializing
            // the Throwable leaks a full stack trace to the client and buries the
            // message a level deeper than clients expect (mirrors the DuplicateData
            // handling just below).
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (DuplicateDataException e){
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        }
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "Delete datasets",
            description = """
                    Delete one or more datasets by `id` or `externalId`.

                    Deleting a dataset does **not** delete the resources or timeseries that
                    reference it — they continue to exist with their `dataSetId` cleared.
                    Remove those separately via `POST /resources/delete` or
                    `POST /timeseries/delete` if you want them gone too.
                    """
    )
    @ApiResponse(responseCode = "204", description = "The datasets were deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "409", description =
            "Someone else changed or deleted one of the datasets while your delete was in " +
                    "flight. No datasets were removed. Re-fetch state and retry.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class)
            ))
    @RequestMapping(value = { "/delete"},
            method = {RequestMethod.POST, RequestMethod.DELETE},
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> delete(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "externalId": "sap_work_orders" }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody
                                    @Schema(implementation = IdCollectionDataWrapper.class)
                                    DataWrapper<IdCollection> form
    ){
        // A data set is the unit access is granted on, so managing one is an operator action:
        // creating, renaming or re-parenting it changes what existing grants cover.
        // Requires an all-datasets write grant. See DATASET_ACL_SETUP.md.
        dataSecurity.assertCanManageDataSets();
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
        // Let the concurrency conflict reach ConcurrencyExceptionHandler as a 409 — the broad
        // RuntimeException catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        }
        // Let dataset-ACL denials surface as 403 instead of being masked below.
        catch (org.springframework.security.access.AccessDeniedException e){
            throw e;
        }
        catch (PulsarClientException | RuntimeException e){
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.noContent().build();
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "Full-text search datasets",
            description = """
                    Free-text search across data sets. The phrase is matched against `name`,
                    `externalId` and `description`. Matching is fuzzy and word-aware, and results
                    are ranked by relevance.

                    ### Narrowing the results
                    `filter` is optional and takes the same criteria as `POST /datasets/filter`.
                    It only ever *removes* matches — the phrase decides what the candidates are.
                    Omit it for no narrowing.

                    If you don't need a phrase at all, use `POST /datasets/filter`: a structured
                    query on its own is faster and more predictable than one bolted to a search.

                    `limit` caps the result size (default 100, max 1000). No match is an empty
                    list, not an error.

                    ### Result order
                    Ranked by relevance (`ts_rank`), strongest match first, with `id` as a
                    tie-break so equal-scoring rows keep a stable order and repeated identical
                    requests agree. Ranking means the database scores and sorts every match before
                    applying `limit`, so a very broad phrase costs more than a narrow one.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Datasets ranked by how well they match the search phrase.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = DataSetDataWrapper.class)
    ))
    @ApiResponse(responseCode = "400", description =
            "The request failed validation — usually a missing or too-short `search.query`. " +
                    "Response lists the offending fields.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)
            ))
    @RequestMapping(value = "/search", method = RequestMethod.POST, produces = { "application/json", "application/xml" })
    public ResponseEntity<?> get(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Search phrase, optional filter, optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            // No `schema = @Schema(implementation = ...)`: a class literal cannot
                            // name a parameterized type, and springdoc derives the schema from the
                            // handler's parameter type anyway, which does carry the filter type.
                            examples = @ExampleObject(value = """
                                    {
                                      "search": { "query": "work order" },
                                      "filter": { "metadata": { "source_system": "sap" } },
                                      "limit": 50
                                    }
                                    """)
                    )
            )
            // @Valid, like the three sibling searches. Without it the only validation was the
            // hand-rolled pass below, whose bare-string 400 was a different shape from theirs for
            // the same mistake.
            @Valid @RequestBody SearchBody<DataSetFilter> form
    ){
        try{
            DataWrapper<DataSetModel> items = dataSetService.search(form);
            return new ResponseEntity<>(items, HttpStatus.OK);
        }
        catch (ObjectNotFoundException e){
            // Rethrow so ObjectNotFoundExceptionHandler renders the shared RFC 9457
            // problem+json body. Catching it here returned a bare JSON string, so the API
            // had two different shapes for the same 404.
            throw e;
        }
    }

    @Tag(name = "Data sets")
    @Operation(
            summary = "List policies available to datasets",
            description = """
                    Return every access policy that a dataset can be associated with. Use this
                    when building a dataset form and you need to show the user the policies
                    they can pick from.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Every policy in your tenant.", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = PolicyDataWrapper.class)
    ))
    @RequestMapping(value = "/policies", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    public ResponseEntity<DataWrapper<Resource>> listPolicyNodes() {

        List<PolicyEntity> nodes = policyEntityRepository.findAll();

        // Convert using the standard ResourceTransformer (dataset pattern)
        var resources = ResourceTransformer.from(nodes);

        DataWrapper<Resource> wrapper = new DataWrapper<>();
        wrapper.setItems(resources);

        return ResponseEntity.ok(wrapper);
    }

}
