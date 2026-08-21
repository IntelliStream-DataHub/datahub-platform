// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.policy.PolicyCandidate;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.repositories.node.NodeSort;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyWarning;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.messaging.events.DatapointCudPublishEvent;
import ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent;
import ai.intellistream.datahub.api.responses.*;
import ai.intellistream.datahub.clickhouse.ClickHouseDatapointService;
import ai.intellistream.datahub.clickhouse.DatapointBinaryConverter;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.jpa.dto.*;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.ResourceCudMessage;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantContextExecutorService;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.TimeseriesFields;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import ai.intellistream.datahub.transformers.TimeseriesTransformer;
import ai.intellistream.datahub.util.DatapointsResult;
import ai.intellistream.datahub.validation.resources.AtLeastOneNotNull;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.*;


/**
 * The TimeseriesService class provides functionality to manage timeseries data within the system.
 * It includes methods for deleting timeseries, retrieving datapoints, and handling timeseries-related operations.
 * The service integrates with underlying repositories and other supporting services for managing the data flow.
 * Main Responsibilities:
 * - Delete timeseries based on internal or external identifiers, ensuring no existing relationships are violated.
 * - Retrieve timeseries datapoints from ClickHouse or other storage, with validation of input filters.
 * - Handle relations to timeseries and process related edge data.
 * - Integrate with other services to fetch and process data efficiently.
 */
@Service
@Slf4j
@AllArgsConstructor
public class TimeseriesService {

    private final NodeRepository nodeRepository;

    private final ResourceService resourceService;

    private final NodeService nodeService;

    private final DatasetClosureService datasetClosureService;

    private final EdgeRepository edgeRepository;

    private final ApplicationEventPublisher applicationEventPublisher;

    private final Producer<DataWrapperBin> allDatapointProducer;

    @Qualifier("datapointIngestCounter")
    private final LiveIngestCounter datapointIngestCounter;

    // Upper bound on waiting for a ClickHouse datapoint fetch to complete its future. Generous
    // enough for a big streaming query (which can run for minutes; also matches the Valkey chunk
    // TTL), but bounded so a fetch that never completes can't block the request thread forever.
    private static final long RETRIEVE_TIMEOUT_SECONDS = 300;

    private final Validator validator;

    private final ClickHouseDatapointService clickHouseDatapointService;

    private final DataSecurity dataSecurity;

    private final ValkeyService valkeyService;

    private final JsonMapper jsonMapper;
    private final TimeseriesRepository timeseriesRepository;
    private final DataSetRepository datasetEntityRepository;

    /** The naming policy, applied to every timeseries create and rename. See {@link PolicyEnforcement}. */
    private final PolicyEnforcement policyEnforcement;

    /**
     * Used by {@link #insertDatapoints} to scope the database reads, instead of annotating the whole
     * method. Spring Boot auto-configures this from the single {@code PlatformTransactionManager}.
     */
    private final TransactionTemplate transactionTemplate;

    // Bounded executor for ClickHouse datapoint queries. Replaces the previous `new Thread(...)`
    // per filter, which let any caller fan out unlimited threads. Sized off the host CPU count
    // because the work is I/O-bound (network + remote query execution) — the real limit is
    // ClickHouse's concurrency, not local CPU. CallerRuns pushes back onto the request thread
    // when the queue saturates, so callers slow down instead of work being dropped.
    //
    // NUMA note: on Java 21 / Linux, availableProcessors() calls sched_getaffinity(2), so when
    // the JVM is launched under `numactl --cpunodebind=<node>` (or --physcpubind) it sees only
    // the CPUs in the process's affinity mask. On a 2-socket box pinned to one node this means
    // CORES == cores-per-node (e.g. 40, not 80), which is what we want — the pool scales to the
    // resources the JVM can actually use. To force a specific value regardless of affinity, pass
    // `-XX:ActiveProcessorCount=N`. Beware: `numactl --membind` alone does not set CPU affinity,
    // so CORES would still reflect the full socket count in that case.
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    // Wrapped so each query task inherits the submitting request thread's TenantContext; the
    // ThreadLocal doesn't cross the pool boundary on its own, and downstream caching
    // (ValkeyService) resolves its connection from TenantContext.
    private final ExecutorService clickHouseQueryExecutor = TenantContextExecutorService.wrap(new ThreadPoolExecutor(
            CORES,
            CORES * 4,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(512, CORES * 16)),
            new ClickHouseQueryThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    ));

    @PreDestroy
    void shutdownClickHouseQueryExecutor() {
        clickHouseQueryExecutor.shutdown();
        try {
            if (!clickHouseQueryExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                clickHouseQueryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            clickHouseQueryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class ClickHouseQueryThreadFactory implements ThreadFactory {
        private final AtomicLong seq = new AtomicLong();

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, "clickhouse-query-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Deletes timeseries data based on the provided input data. It identifies timeseries
     * either by their internal IDs or external hashed IDs and deletes both the timeseries
     * and any related edges. If any relationships exist for the timeseries being deleted,
     * a validation check is performed, throwing an exception if a conflict exists.
     *
     * <p>The data-points follow the definition: each deleted timeseries also gets a fully-open
     * {@code DATAPOINTS}/{@code DELETE} message so the stateless consumer purges its ClickHouse
     * rows. That purge is asynchronous — this method returns (and the endpoint answers 204) once
     * Postgres has committed, before ClickHouse has actually dropped the rows.
     *
     * @param apiReqData The input data containing a collection of IDs or external IDs
     *                   representing the timeseries to be deleted.
     * @throws PulsarClientException If there is an issue communicating with the message producer.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTimeseries(DataWrapper<IdCollection> apiReqData) throws PulsarClientException{

        // Resolve the targeted rows from the DB by id OR externalId (the request usually carries
        // only externalId; findAllByIdCollection hashes it and looks it up). The returned entities
        // are managed rows, so they always have their internal primary-key id populated — we use
        // *that* id downstream, never the (typically null) id from the incoming request.
        List<TimeseriesEntity> timeseries = timeseriesRepository.findAllByIdCollection(apiReqData.getItems());

        if(timeseries.isEmpty()){
            return;
        }

        Set<Long> resourceIdList = timeseries
                .stream()
                .map(TimeseriesEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Delegate the actual node + edge removal to the shared resource delete pipeline so
        // timeseries obey the same rules as every other node type. That pipeline handles the
        // per-dataset write check, the subscription guard (so no subscriber silently loses its
        // feed), row locking, the Neo4j connectivity check (which replaces the old start-edge guard
        // this method used to apply), the incident-edge cascade, and the Pulsar event. The Neo4j
        // listener treats RESOURCE_AND_RELATION and TIMESERIES deletes identically, so emitting the
        // generic event object there is a no-op for the graph sync. We pass the DB-resolved internal
        // ids, so resourceService.delete never has to re-resolve.
        GraphDataWrapper<Resource, EdgeProxy> toDelete = new GraphDataWrapper<>();
        for(Long id : resourceIdList){
            Resource r = new Resource();
            r.setId(id);
            toDelete.getNodes().add(r);
        }
        resourceService.delete(toDelete);

        // The node row is gone, but its data-points live in ClickHouse, which resourceService knows
        // nothing about. Emit one fully-open DATAPOINTS/DELETE per timeseries (no begin, no end =
        // every row for that timeseries_id) so the stateless consumer purges them; without this the
        // rows would linger forever, unreachable but still paying for storage. Published through the
        // after-commit listener: a rollback here must not wipe the data of a timeseries that still
        // exists. Read the entity fields before returning — resourceService.delete has already
        // scheduled the row removal, and valueType is what picks the ClickHouse table.
        DataWrapperMessage purge = new DataWrapperMessage(
                EventObject.DATAPOINTS, EventAction.DELETE, new ArrayList<>(), TenantContext.getTenantId());
        for(TimeseriesEntity ts : timeseries){
            var purgeFilter = new DataCollectionString();
            purgeFilter.setId(ts.getId());
            purgeFilter.setExternalId(ts.getExternalId());
            purgeFilter.setValueType(ts.getValueType().getName());
            purge.getItems().add(purgeFilter);

            // Drop the cached latest datapoint too. The key is derived from the externalId, so a
            // timeseries later recreated under the same externalId would otherwise be served this
            // dead value — the entry has no TTL. Evicting is always safe: a miss falls back to
            // ClickHouse (see fetchLatestDatapoint).
            valkeyService.deleteLatestDatapoint(ts.getExternalId());
        }
        applicationEventPublisher.publishEvent(
                new DatapointCudPublishEvent(DatapointBinaryConverter.toBinary(purge)));
    }

    /**
     * Retrieves a collection of datapoints using the provided data retriever,
     * which contains the necessary filters and parameters for the retrieval operation.
     * The method validates the input data retriever and processes the request by either
     * fetching new datapoints or retrieving datapoints from existing cursors. It handles
     * any bad request errors by analyzing constraint violations and encapsulating the
     * validation errors. The data is then transformed and returned in a structured format.
     *
     * @param apiReqData the data retriever containing the list of filters and parameters
     *                   for retrieving datapoints.
     * @return a DataWrapper object containing a collection of retrieved datapoints.
     */
    public DataWrapper<DataCollection<?>> retrieveDatapointsFromCH(DataRetriever<RetrieveFilter> apiReqData) {

        Set<ConstraintViolation<DataRetriever<RetrieveFilter>>> violations = validator.validate(apiReqData);
        if (!violations.isEmpty()) {
            ResponseError<BadRequestError> errors = new ResponseError<>();
            // Handle violations
            for (ConstraintViolation<DataRetriever<RetrieveFilter>> violation : violations) {
                String message = violation.getMessage();
                var de = new BadRequestError();
                de.setMessage(message);
                if(violation.getConstraintDescriptor().getAnnotation() instanceof AtLeastOneNotNull){
                    String[] fieldNames = (String[])violation.getConstraintDescriptor().getAttributes().get("fieldNames");
                    de.getFields().add(Map.of(violation.getPropertyPath().toString(), String.join(",", fieldNames)));
                } else {
                    Object invalidValue = violation.getInvalidValue();
                    de.getFields().add(Map.of(violation.getPropertyPath().toString(), invalidValue.toString()));
                }
                errors.setError(de);
            }
            throw new BadRequestException(errors);
        }

        DataWrapper<DataCollection<?>> results = new DataWrapper<>();

        // Check for cursor
        Set<String> cursors = new HashSet<>();
        for(RetrieveFilter filter : apiReqData.getItems()){
            if(filter.getCursor() != null){
                cursors.add(filter.getCursor());
            }
        }

        // If no cursor, retrieve data
        if(cursors.isEmpty()){
            List<CompletableFuture<DatapointsResult>> fetchDataFutures = fetchDatapoints(apiReqData);
            try{
                for(CompletableFuture<DatapointsResult> future : fetchDataFutures){
                    // Bounded wait: even if a fetch never completes its future, the request thread
                    // must not block forever (blocked threads would otherwise pile up).
                    DatapointsResult result = future.get(RETRIEVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if(result.getNode() != null){
                        if(result.getCursorId() != null){
                            log.debug("We can now retrieve datapoints from Valkey: " + result.getCursorId());
                            Thread.sleep(1000); // Sleep for 1 second so Valkey can catch up
                            // Todo: rewrite this so the thread instead wait for valkey data chunk to be available
                        }
                        try{
                            DataCollection<?> dc = transformJsonToDataCollection(result);
                            results.getItems().add(dc);
                        } catch (JsonProcessingException e){
                            log.error("Failed to transform datapoint result to DataCollection", e);
                            throw new RuntimeException(e);
                        }
                    }
                }
            } catch (TimeoutException e) {
                log.error("Timed out retrieving datapoints from ClickHouse after {}s", RETRIEVE_TIMEOUT_SECONDS, e);
                throw new RuntimeException("Timed out retrieving datapoints from ClickHouse", e);
            } catch (ExecutionException e) {
                // A fetch failed (e.g. ClickHouse error): surface it as a 5xx instead of returning a
                // 200 with silently missing timeseries.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Failed to fetch datapoints", cause);
                throw new RuntimeException("Failed to fetch datapoints", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while retrieving datapoints", e);
            }
        } else {
            // Fetch cursor datapoints
            long maxElements = 100000 / cursors.size();
            for(String cursor : cursors){
                DataCollection<?> dc = valkeyService.fetchDatapointsFromCursor(cursor, maxElements);
                if(valkeyService.getListSize(cursor) > 0){
                    dc.setNextCursor(cursor);
                } else {
                    valkeyService.delete(cursor);
                }
                if(!dc.getDatapoints().isEmpty()){
                    results.getItems().add(dc);
                }
            }
        }

        return results;
    }

    /**
     * Transforms a given DatapointsResult JSON structure into an appropriate DataCollection
     * object, depending on the type of value stored in the timeseries data.
     *
     * @param result the DatapointsResult object containing the JSON data to be transformed.
     * @return a DataCollection object holding the transformed data points corresponding to the
     *         appropriate data type (e.g., DatapointAggsDTO, DatapointBigIntDTO, DatapointFloatDTO,
     *         DatapointNumericDTO, DatapointTextDTO).
     * @throws JsonProcessingException if an error occurs while processing the JSON data.
     */
    private DataCollection<?> transformJsonToDataCollection(DatapointsResult result)
            throws JsonProcessingException {
        if(result.hasAggregates()){
            DataCollection<DatapointAggsDTO> dc = getDatapoint(result);
            for(String dpString : result.getDatapoints()){
                DatapointAggsDTO dp = jsonMapper.readValue(dpString, DatapointAggsDTO.class);
                dc.getDatapoints().add(dp);
            }
            return dc;
        } else {
            Integer id = result.getNode().getValueType().getId();
            if(id == BIGINT){
                DataCollection<DatapointBigIntDTO> dc = getDatapoint(result);
                for(String dpString : result.getDatapoints()){
                    DatapointBigIntDTO dp = jsonMapper.readValue(dpString, DatapointBigIntDTO.class);
                    dc.getDatapoints().add(dp);
                }
                return dc;
            } else if(id == FLOAT || id == FLOAT32){
                DataCollection<DatapointFloatDTO> dc = getDatapoint(result);
                for(String dpString : result.getDatapoints()){
                    DatapointFloatDTO dp = jsonMapper.readValue(dpString, DatapointFloatDTO.class);
                    dc.getDatapoints().add(dp);
                }
                return dc;
            } else if(id == NUMERIC || id == DECIMAL32){
                DataCollection<DatapointNumericDTO> dc = getDatapoint(result);
                for(String dpString : result.getDatapoints()){
                    DatapointNumericDTO dp = jsonMapper.readValue(dpString, DatapointNumericDTO.class);
                    dc.getDatapoints().add(dp);
                }
                return dc;
            } else if(id == TimeseriesValueType.TEXT || id == MIXED){
                DataCollection<DatapointTextDTO> dc = getDatapoint(result);
                for(String dpString : result.getDatapoints()){
                    DatapointTextDTO dp = jsonMapper.readValue(dpString, DatapointTextDTO.class);
                    dc.getDatapoints().add(dp);
                }
                return dc;
            }
        }

        throw new RuntimeException("Error with value type for processing json");
    }

    private static <T> DataCollection<T> getDatapoint(DatapointsResult result) {
        DataCollection<T> dc = new DataCollection<>();
        dc.setId(result.getNode().getId());
        dc.setExternalId(result.getNode().getExternalId());
        dc.setUnit(result.getNode().getUnit());
        dc.setUnitExternalId(result.getNode().getUnitExternalId());
        if(result.getTotalBatches() > 0){
            dc.setNextCursor(result.getCursorId());
        }
        return dc;
    }

    /**
     * Fetches datapoints asynchronously for the provided set of filters and nodes.
     * This method processes the input data, validates the presence of time series,
     * checks data access permissions, and retrieves raw or aggregated datapoints
     * from a ClickHouse database. The tasks are executed in separate threads.
     *
     * @param apiReqData the data retriever containing the list of filters, IDs, external IDs,
     *                   and other metadata used to retrieve datapoints for the specified nodes.
     * @return a list of CompletableFuture objects representing the results of datapoint retrieval
     *         tasks, where each CompletableFuture resolves to a DatapointsResult.
     */
    private List<CompletableFuture<DatapointsResult>>
    fetchDatapoints(DataRetriever<RetrieveFilter> apiReqData) {
        List<CompletableFuture<DatapointsResult>> dataFutures = new ArrayList<>();

        Set<Long> idSet = new HashSet<>();
        Set<String> externalIdSet = new HashSet<>();
        for(RetrieveFilter f : apiReqData.getItems() ){
            if (f.getId() != null) idSet.add(f.getId());
            if( f.getExternalId() != null ) externalIdSet.add(f.getExternalId());
        }
        // Narrow the lookup to the caller's readable datasets in SQL. Timeseries in datasets the
        // caller can't read simply aren't found, so their filter yields an empty result below.
        List<TimeseriesEntity> nodes;
        if (dataSecurity.hasReadAccessToEverything()) {
            nodes = timeseriesRepository.findAllByIdOrExternalId(idSet, externalIdSet);
        } else {
            Set<Long> allowed = dataSecurity.readableDataSetIds();
            nodes = allowed.isEmpty()
                    ? new ArrayList<>()
                    : timeseriesRepository.findAllByIdOrExternalIdAndDataSetIdIn(idSet, externalIdSet, allowed);
        }
        if(!nodes.isEmpty()){

            long maxDatapoints =
                    apiReqData.getLimit() == 0 ?
                            100000 / apiReqData.getItems().size() :
                            apiReqData.getLimit() / apiReqData.getItems().size();

            for(RetrieveFilter f : apiReqData.getItems() ){

                CompletableFuture<DatapointsResult> taskFuture = new CompletableFuture<>();
                dataFutures.add(taskFuture);

                final Optional<TimeseriesEntity> optionalNode = nodes.stream()
                        .filter(n -> n.getId().equals(f.getId()) || n.getExternalId().equals(f.getExternalId()) )
                        .findFirst();

                if( optionalNode.isPresent() ){

                    TimeseriesEntity node = optionalNode.get();
                    // Submit query to the shared bounded executor. CallerRuns applies backpressure
                    // on saturation; taskFuture still resolves on the thread that runs the task.
                    // TenantContext is propagated onto the worker by clickHouseQueryExecutor
                    // (TenantContextExecutorService); tenantId is still passed explicitly because the
                    // ClickHouse query path takes it as an argument.
                    final String tenantId = TenantContext.getTenantId();
                    clickHouseQueryExecutor.execute(() ->
                            clickHouseDatapointService.prepareDatapointsFetching(f, maxDatapoints, taskFuture, node, tenantId));
                } else {
                    log.warn("Node not found for externalId: {}", f.getExternalId());
                    taskFuture.complete(new DatapointsResult());

                    ResponseError<BadRequestError> error = new ResponseError<>();
                    BadRequestError badRequestError = new BadRequestError();
                    badRequestError.setMessage((
                            "Time series entity for resource with id: %s does not exist. " +
                            "To fix this error, please update the time series object."
                    ).formatted(f.getId()));
                    error.setError(badRequestError);
                    throw new BadRequestException(error);
                }
            }
        }

        return dataFutures;
    }

    /**
     * Saves the provided timeseries data and creates associated entities in the database.
     *
     * @param apiReqData The DataWrapper containing a collection of Timeseries objects to be saved.
     *                   Each Timeseries object is validated, and external IDs are checked for uniqueness.
     * @return A DataWrapper containing the saved Timeseries objects with updated information.
     * @throws ConstraintViolationException if any validation errors occur for the Timeseries objects.
     * @throws BadRequestException if a bad request is detected during processing.
     * @throws RuntimeException if an unexpected error or serious issue occurs during execution.
     */
    @Transactional(rollbackFor = Exception.class)
    public DataWrapper<Timeseries> save(DataWrapper<Timeseries> apiReqData)
            throws ConstraintViolationException, BadRequestException, RuntimeException {

        Collection<Long> externalIdHashCollection = new HashSet<>();
        List<Map<String, String>> duplicatedInBatch = new ArrayList<>();
        // Validate Entities
        apiReqData.getItems().forEach(ts -> {
            Set<ConstraintViolation<Timeseries>> errors = validator.validate(ts);
            if (!errors.isEmpty()) {
                throw new ConstraintViolationException(errors);
            }
            // Deny creating a timeseries in a dataset the caller can't write (null dataset →
            // requires the write-all grant).
            dataSecurity.assertCanWriteDataSet(ts.getDataSetId());
            // Two items with the same externalId in ONE request would pass the DB-existence check
            // (neither row exists yet) and only die on the unique constraint — catch it here.
            if (!externalIdHashCollection.add(ExternalIds.hash(ts.getExternalId()))) {
                duplicatedInBatch.add(Map.of("externalId", ts.getExternalId()));
            }
        });
        if (!duplicatedInBatch.isEmpty()) {
            throw duplicateExternalIdException(duplicatedInBatch);
        }

        // Referenced datasets must exist (400) and externalIds must be free (409). Do these before the
        // insert — otherwise a missing dataset trips a FK violation and a duplicate externalId trips
        // the unique constraint, and both surface as a generic 500 from the catch below.
        Set<Long> dataSetIds = apiReqData.getItems().stream()
                .map(Timeseries::getDataSetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!dataSetIds.isEmpty()) {
            validateDataSet(dataSetIds);
        }
        checkForExistingExternalIds(externalIdHashCollection);

        // Naming policy, over the WHOLE batch and before anything is mapped or persisted. A
        // timeseries is a node like any other — same table, same external_id_hash unique index — so
        // the same convention governs it. A NOT_OK verdict throws, so a rejected batch creates
        // nothing rather than creating some rows and rolling them back.
        List<PolicyFinding> policyWarnings = policyEnforcement.check(namingCandidatesForCreate(apiReqData.getItems()));

        try{
            Collection<TimeseriesEntity> tsEntities = nodeService.mapTimeseriesFrom(apiReqData.getItems());
            tsEntities =timeseriesRepository.saveAll(tsEntities);
            // Also create edges for timeseries
            Collection<EdgeEntity> edgeEntities = createEdges(apiReqData.getItems());
            edgeRepository.saveAll(edgeEntities);

            apiReqData.setItems(TimeseriesTransformer.from(tsEntities, edgeEntities));

            // All is good so far, send create timeseries message
            // Create resource in Neo4J
            var msg = new ResourceCudMessage(EventAction.CREATE, EventObject.TIMESERIES, TenantContext.getTenantId());
            List<Resource> resources = ResourceTransformer.from(tsEntities);
            msg.setResources(resources);
            msg.setEdges(edgeEntities);
            applicationEventPublisher.publishEvent(new ResourceCudPublishEvent(msg));

            // Findings reference node_id, which only exists now that the rows are saved. A rejected
            // batch never reaches here, which is the intent: NOT_OK leaves no entity to attach to.
            recordPolicyWarnings(policyWarnings, tsEntities);
            apiReqData.setWarnings(policyWarnings.stream().map(PolicyWarning::from).toList());

        } catch (BadRequestException e){
            throw new BadRequestException(e.getError());
        } catch (RuntimeException e){
            throw new RuntimeException(e.getMessage(), e);
        }

        return apiReqData;
    }

    /**
     * Reduce a create batch to what the naming policy needs to judge it. Index is the item's
     * position in the submitted batch, so a rejection names which item was wrong.
     */
    private static List<PolicyCandidate> namingCandidatesForCreate(Collection<Timeseries> items) {
        List<PolicyCandidate> candidates = new ArrayList<>(items.size());
        int index = 0;
        for (Timeseries ts : items) {
            candidates.add(PolicyCandidate.forCreate(
                    index++, ts.getExternalId(), ts.getName(), ts.getDataSetId()));
        }
        return candidates;
    }

    /** Persist warnings once the entities exist, mapping each finding's external id to its node id. */
    private void recordPolicyWarnings(List<PolicyFinding> warnings, Collection<TimeseriesEntity> entities) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        Map<String, PolicyEnforcement.WrittenEntity> writtenByExternalId = new HashMap<>();
        for (TimeseriesEntity entity : entities) {
            if (entity.getExternalId() != null && entity.getId() != null) {
                // Only the id off the lazy dataset association: the persistence context answers that
                // from the foreign key it already holds, where any other field would fetch the whole
                // dataset row per timeseries just to label a finding.
                Long dataSetId = entity.getDataSet() == null ? null : entity.getDataSet().getId();
                writtenByExternalId.put(entity.getExternalId(),
                        new PolicyEnforcement.WrittenEntity(entity.getId(), dataSetId));
            }
        }
        policyEnforcement.recordWarnings(warnings, writtenByExternalId);
    }


    // Reject a create whose externalId is already taken with a 409, instead of letting the DB
    // constraint surface as a 500. The unique constraint (node_external_id_hash_key) spans the
    // whole node table — resources, datasets and timeseries alike — so the check must query ALL
    // node types, not just timeseries.
    private void checkForExistingExternalIds(Collection<Long> externalIdHashes) {
        if (externalIdHashes.isEmpty()) {
            return;
        }
        List<NameAndExternalId> existing =
                nodeRepository.findAllByExternalIdHashIn(externalIdHashes.stream().toList(), NameAndExternalId.class);
        if (existing.isEmpty()) {
            return;
        }
        Collection<Map<String, String>> duplicated = existing.stream()
                .map(e -> Map.of("externalId", e.getExternalId()))
                .collect(Collectors.toList());
        throw duplicateExternalIdException(duplicated);
    }

    private DuplicateDataException duplicateExternalIdException(Collection<Map<String, String>> duplicated) {
        var duplicateError = new DuplicateError();
        duplicateError.setCode(409);
        duplicateError.setMessage("Timeseries with externalId already exists.");
        duplicateError.setDuplicated(duplicated);
        ResponseError<DuplicateError> responseError = new ResponseError<>();
        responseError.setError(duplicateError);
        return new DuplicateDataException(responseError);
    }

    private void validateDataSet(Collection<Long> dataSetIds) {
        List<DatasetEntity> results = datasetEntityRepository.findAllById(dataSetIds.stream().toList());
        List<Long> dataSetNotFound = new ArrayList<>();
        dataSetIds.forEach(id -> {
            boolean found = false;
            for (DatasetEntity result : results) {
                if (Objects.equals(result.getId(), id)) {
                    found = true;
                    break;
                }
            }
            if( !found ){
                dataSetNotFound.add(id);
            }
        });
        if(!dataSetNotFound.isEmpty()){
            ResponseError<BadRequestError> responseError = new ResponseError<>();
            var e = new BadRequestError();
            e.setMessage("Timeseries with dataSetIds does not exist.");
            e.setFields(List.of(Map.of("dataSetId", String.join(",", dataSetNotFound.stream().map(Object::toString).toList()) )));
            responseError.setError(e);
            throw new BadRequestException(responseError);
        }
    }

    /**
     * Inserts datapoints into timeseries entities based on the provided data wrapper.
     * This method locates the appropriate timeseries entities, processes the datapoints,
     * and sends the data to the corresponding producers.
     *
     * @param data A wrapper containing a collection of datapoints to be inserted
     *             along with the corresponding timeseries identifiers.
     * @return A DataWrapper object containing possible error responses for entries
     *         that could not be processed successfully.
     * @throws PulsarClientException If there is an issue with producing messages
     *                               to the Pulsar client.
     */
    /**
     * Accepts datapoints for one or more timeseries and publishes them to Pulsar.
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> This path performs no database
     * writes: it reads the timeseries, checks the caller may write to its dataset, validates the
     * values, and publishes. Annotating the whole method held a pooled database connection across
     * the Valkey round trips and the Pulsar send, which is why {@code blockIfQueueFull} had to stay
     * off: blocking on a full producer queue would have blocked while holding that connection, and
     * could exhaust the pool.
     *
     * <p>Instead the reads run inside a short transaction and the network I/O runs outside it. The
     * transaction is still needed, and cannot simply be dropped: {@code NodeEntity.dataSet} is a
     * lazy association that {@link DataSecurity#assertCanWrite} dereferences, and
     * {@code open-in-view} is off, so without a persistence context the permission check would fail.
     *
     * <p>The publish stays <strong>synchronous</strong>. Pulsar is the source of truth for
     * datapoints, with no Postgres copy to reconcile against, so returning before the broker has
     * acknowledged would turn a crash into silent data loss after the caller was told the write
     * succeeded. This is also why the path does not go through {@code AfterCommitMessagePublisher}
     * like the CUD producers do: that exists to avoid publishing what a transaction rolled back,
     * a problem this path does not have.
     *
     * <p>One behaviour change came with the split. Validation now covers the whole request before
     * anything is published, so a bad value in the fifth collection means none of the first four
     * are sent either. Previously they were already in Pulsar when the exception was thrown, and a
     * client retrying after the error would double-insert them.
     *
     * @param data A wrapper containing a collection of datapoints to be inserted
     *             along with the corresponding timeseries identifiers.
     * @return A DataWrapper object containing possible error responses for entries
     *         that could not be processed successfully.
     * @throws PulsarClientException If there is an issue with producing messages
     *                               to the Pulsar client.
     */
    public DataWrapper<?> insertDatapoints(DataWrapper<DatapointsCollection> data)
            throws PulsarClientException {

        // Phase 1: resolve, authorise and validate. Needs the persistence context; no I/O.
        PreparedDatapointInsert prepared = transactionTemplate.execute(status -> prepareDatapointInsert(data));

        // Phase 2: the network I/O, with no database connection held.
        for (PendingDatapointPublish pending : prepared.pending()) {
            addToLatestValuesCache(pending.externalId(), pending.latestDatapoint());
            allDatapointProducer.send(pending.message());
            datapointIngestCounter.recordIngested(TenantContext.getTenantId(), pending.datapointCount());
        }
        return prepared.response();
    }

    /** A collection that passed validation, ready to publish once the transaction has ended. */
    private record PendingDatapointPublish(
            String externalId,
            DatapointString latestDatapoint,
            DataWrapperBin message,
            long datapointCount) {
    }

    /** The outcome of the read phase: what could not be resolved, and what is ready to publish. */
    private record PreparedDatapointInsert(
            DataWrapper<BadRequestError> response,
            List<PendingDatapointPublish> pending) {
    }

    /**
     * The read half of {@link #insertDatapoints}, run inside the transaction. Resolves each
     * timeseries, checks write permission, validates every value against the declared type, and
     * builds the message to publish. Performs no I/O of its own.
     */
    private PreparedDatapointInsert prepareDatapointInsert(DataWrapper<DatapointsCollection> data) {
        DataWrapper<BadRequestError> responseData = new DataWrapper<>();
        List<PendingDatapointPublish> pending = new ArrayList<>();

        for(DatapointsCollection entry : data.getItems()){

            // Find the timeseries entity
            TimeseriesEntity ts = timeseriesRepository.findByIdOrExternalId(entry.getId(), entry.getExternalId()).orElse(null);

            if(ts == null) {
                String externalId = entry.getExternalId();
                String id = String.valueOf(entry.getId());
                var de = new BadRequestError();
                de.setCode(404);
                de.setMessage("Could not find following timeseries.");
                de.getFields().add(Map.of("externalId", externalId, "id", id));
                responseData.getItems().add(de);
                continue;
            }

            // Writing data-points is a write to the timeseries' dataset.
            dataSecurity.assertCanWrite(ts);

            // Define message type and actions
            DataWrapperMessage insertData = new DataWrapperMessage(
                    EventObject.DATAPOINTS,
                    EventAction.CREATE,
                    new ArrayList<>(),
                    TenantContext.getTenantId()
            );

            // We found timeseries entity and can continue with datapoint insert
            DatapointString latestDatapoint = null;
            for(DatapointString dp : entry.getDatapoints()){
                switch(ts.getValueType().getId()){
                    case BIGINT -> {
                        try {
                            Long.parseLong(dp.getValue());
                        } catch (NumberFormatException err) {
                            throw new RuntimeException("Could not parse value: " + dp.getValue() + " to long");
                        }
                    }
                    case FLOAT, FLOAT32, NUMERIC -> {
                        try {
                            Double.parseDouble(dp.getValue());
                        } catch (NumberFormatException err) {
                            throw new RuntimeException("Could not parse value: " + dp.getValue() + " to double");
                        }
                    }
                    case DECIMAL32 -> {
                        // Only check the value is a number; out-of-range magnitudes are clamped (not
                        // rejected) and excess decimals rounded at write time, so the batch survives
                        // a sensor glitch.
                        try {
                            new BigDecimal(dp.getValue());
                        } catch (NumberFormatException err) {
                            throw new RuntimeException("Could not parse value: " + dp.getValue() + " to a decimal");
                        }
                    }
                    case MIXED -> {
                        // Accepts both numbers and text — the consumer routes each value to the
                        // numeric or the text column. @NotBlank already rejects empty values.
                    }
                    default -> throw new RuntimeException("Unsupported value type: " + ts.getValueType().getName());
                }
                addData(ts, insertData, dp);

                // Find and set the latest datapoint
                if(latestDatapoint != null){
                    ZonedDateTime timestamp = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(dp.getTimestamp());
                    ZonedDateTime latestDatapointTimestamp = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(latestDatapoint.getTimestamp());
                    if(timestamp.isAfter(latestDatapointTimestamp)){
                        latestDatapoint = dp;
                    }
                } else {
                    latestDatapoint = dp;
                }
            }

            if(!insertData.getItems().isEmpty()){
                long sentCount = insertData.getItems().stream().mapToLong(it -> it.getDatapoints().size()).sum();
                // Converted here, while the entity is still attached, so phase 2 is purely I/O.
                pending.add(new PendingDatapointPublish(
                        ts.getExternalId(),
                        latestDatapoint,
                        DatapointBinaryConverter.toBinary(insertData),
                        sentCount));
            }
        }
        return new PreparedDatapointInsert(responseData, pending);
    }

    private static void addData(
            TimeseriesEntity ts,
            DataWrapperMessage dc,
            DatapointString dp
    ) {
        long timeseriesId = ts.getId();
        DataCollectionString e =
                dc.getItems().stream()
                        .filter(it -> it.getId() == timeseriesId)
                        .findFirst()
                        .orElse(null);
        if(e == null){
            e = new DataCollectionString();
            e.setId(timeseriesId);
            e.setExternalId(ts.getExternalId());
            e.setValueType(ts.getValueType().getName());
            e.setDatapoints( new HashSet<>() );
            dc.getItems().add(e);
        }
        e.getDatapoints().add(dp);
    }

    /**
     * Adds or updates a datapoint in the latest values cache for a given external ID.
     * If a datapoint for the external ID already exists in the cache, it compares the timestamps
     * and updates only if the new datapoint has a more recent timestamp.
     *
     * @param externalId the unique identifier for the external data source.
     * @param dp the datapoint object to be added or checked against the cache.
     */
    private void addToLatestValuesCache(String externalId, DatapointString dp) {
        try {
            DatapointString obj = valkeyService.fetchLatestDatapoint(externalId);

            if(obj == null){
                valkeyService.setLatestDatapoint(externalId, dp);
            } else {
                ZonedDateTime latestTime = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(dp.getTimestamp());
                ZonedDateTime latestSavedTime = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(obj.getTimestamp());
                if(latestTime.isAfter(latestSavedTime)){
                    valkeyService.setLatestDatapoint(externalId, dp);
                }
            }
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);
        }
    }


    /**
     * Full-text search over timeseries, optionally narrowed by a {@link TimeseriesFilter}.
     *
     * <p>One query: the phrase is a predicate beside the filter's, not a separate pass. Search is
     * {@code POST /timeseries/filter} plus a phrase, and reads as that here.
     *
     * <p>It used to run the phrase natively up to a 10 000-row candidate ceiling and then re-ask
     * the filter query about those ids. Beyond the double round trip, the ceiling made the answer
     * wrong: with no ordering on the candidate query, a phrase matching more than 10 000 rows had
     * an arbitrary 10 000 narrowed, so a timeseries matching both could be missing from the result.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Timeseries> search(SearchBody<TimeseriesFilter> searchCmd){
        var data = new DataWrapper<Timeseries>();
        List<TimeseriesEntity> entities = new ArrayList<>();
        String phrase = searchCmd.getSearch() == null ? null : searchCmd.getSearch().getQuery();
        if (phrase != null) {
            TimeseriesFilter filter = searchCmd.getFilter();
            boolean readAll = dataSecurity.hasReadAccessToEverything();
            Set<Long> allowed = readAll ? null : dataSecurity.readableDataSetIds();
            // No readable datasets and not a read-all caller -> nothing to return; skip the query.
            if (!readAll && allowed.isEmpty()) {
                data.setItems(new ArrayList<>());
                return data;
            }

            // dataSetIds on the filter narrow the scope: they become the whole restriction for a
            // read-all caller, and intersect with the readable set for everyone else.
            if (filter != null && filter.getDataSetId() != null) {
                Set<Long> requested = new HashSet<>(visibleDatasetClosure(filter.getDataSetId()));
                allowed = readAll
                        ? requested
                        : requested.stream().filter(allowed::contains).collect(Collectors.toSet());
                if (allowed.isEmpty()) {
                    data.setItems(new ArrayList<>());
                    return data;
                }
            }

            entities = timeseriesRepository.search(phrase, searchCmd.getLimit(), allowed, filter);
        }
        List<Long> nodeIds = entities.stream().map(TimeseriesEntity::getId).collect(Collectors.toList());
        Collection<EdgeEntity> edgeEntities = edgeRepository.findAllByEndIn(nodeIds, EdgeEntity.class);
        var timeseries = TimeseriesTransformer.from(entities, edgeEntities);
        data.setItems(timeseries);

        return data;
    }





    private static boolean notEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    /**
     * Creates and returns a collection of EdgeEntity objects based on a given collection
     * of Timeseries objects. Each EdgeEntity represents a relationship formed between
     * source and target entities defined in the Timeseries.
     *
     * @param timeseriesList the collection of Timeseries objects containing relation data
     *                       to generate edges
     * @return a collection of EdgeEntity objects representing the created edges
     */
    public Collection<EdgeEntity> createEdges(Collection<Timeseries> timeseriesList) {
        // Default relationship type name when creating timeseries
        final String relTypeName = "PUBLISH_DATA_TO";

        // Collection for edges that will be returned
        List<EdgeEntity> edges = new ArrayList<>();

        for(var ts : timeseriesList){
            Map<Long, String> idRelationsMap = new HashMap<>();
            ts.getRelatedResources().forEach( it -> {
                if(it.getId() != null && it.getRelationshipType() !=null ){
                    idRelationsMap.put(it.getId(), it.getRelationshipType());
                } else if(it.getId() != null) {
                    idRelationsMap.put(it.getId(), relTypeName);
                }
            });

            Map<String, String> externalIdRelationsMap = new HashMap<>();
            ts.getRelatedResources().forEach( it -> {
                if(it.getExternalId() != null && it.getRelationshipType() !=null ){
                    externalIdRelationsMap.put(it.getExternalId(), it.getRelationshipType());
                } else if (it.getExternalId() != null){
                    externalIdRelationsMap.put(it.getExternalId(), relTypeName);
                }
            });

            if(validateSourceIds(idRelationsMap.keySet(), externalIdRelationsMap.keySet())){
                idRelationsMap.forEach( (id, relType) -> {
                    RelForm relForm = new RelForm();
                    relForm.setFromId(id);
                    relForm.setToExternalId(ts.getExternalId());
                    relForm.setRelationshipType(relType);
                    var edge = new EdgeEntity();
                    edge = resourceService.mapEdge(edge, relForm);
                    edges.add(edge);
                });
                externalIdRelationsMap.forEach( (externalId, relType) -> {
                    RelForm relForm = new RelForm();
                    relForm.setFromExternalId(externalId);
                    relForm.setToExternalId(ts.getExternalId());
                    relForm.setRelationshipType(relType);
                    var edge = new EdgeEntity();
                    edge = resourceService.mapEdge(edge, relForm);
                    edges.add(edge);
                });
            }

        }
        return edges;
    }

    private boolean validateSourceIds(Set<Long> idSet, Set<String> externalIdSet) {
        ResponseError<BadRequestError> errors = new ResponseError<>();

        List<NameAndEId> nodes = nodeRepository.findAllByIdOrExternalId(idSet,externalIdSet, NameAndEId.class);

        idSet.forEach( id -> {
            boolean nodeFound = false;
            for(var n : nodes) {
                if(n.getId().longValue() == id.longValue()){
                    nodeFound = true;
                    break;
                }
            }
            if(!nodeFound){
                if(errors.getError() == null){
                    var de = new BadRequestError();
                    de.setMessage("Sources cannot be found!");
                    errors.setError(de);
                }
                errors.getError().getFields().add(Map.of("id", String.valueOf(id)));
            }
        });

        if(errors.getError() != null){
            throw new BadRequestException(errors);
        }
        return true;
    }


    @Transactional(readOnly = true)
    public void deleteDatapoints(DataRetriever<DeleteDatapoint> apiReqData) throws PulsarClientException {
        for(DeleteDatapoint ddp : apiReqData.getItems()){
            TimeseriesEntity ts = timeseriesRepository.findByIdOrExternalId(ddp.getId(), ddp.getExternalId()).orElse(null);
            if(ts == null){
                // An item names one of the two identifiers, not both, so the other one is null.
                // Reading either unconditionally turned the documented 400 into a 500: the common
                // case, an externalId that does not exist, died on getId().toString(), and an
                // unknown id died on Map.of rejecting the null externalId.
                String msg = "Time series entity for resource with id: %s, externalId: %s does not exist."
                        .formatted(ddp.getId(), ddp.getExternalId());
                ResponseError<BadRequestError> errors = new ResponseError<>();
                var badRequestError = new BadRequestError();
                badRequestError.setMessage(msg);
                badRequestError.setFields(List.of(Map.of(
                        "id", String.valueOf(ddp.getId()),
                        "externalId", String.valueOf(ddp.getExternalId()))));
                errors.setError(badRequestError);
                throw new BadRequestException(errors);
            }

            // Deleting data-points is a write to the timeseries' dataset (also denies unknown ts).
            dataSecurity.assertCanWrite(ts);

            DataWrapperMessage deleteData = new DataWrapperMessage(EventObject.DATAPOINTS, EventAction.DELETE, new ArrayList<>(), TenantContext.getTenantId());
            var deleteFilter = new DataCollectionString();
            deleteFilter.setId(ts.getId());
            deleteFilter.setExternalId(ts.getExternalId());
            deleteFilter.setValueType(ts.getValueType().getName());
            // Normalise both bounds here, at the API boundary. The endpoint documents epoch millis
            // as an accepted form (like every other timestamp we take), and the window is only
            // parsed much later, in the consumer — so an unparseable bound used to be answered with
            // 204 and then fail repeatedly in the consumer until the message dead-lettered, with
            // the caller none the wiser. Rejecting here turns that into a 400 the caller can act on,
            // and hands the consumer a canonical ISO-8601 UTC string.
            deleteFilter.setInclusiveBegin(normaliseDeleteBound(ddp, ddp.getInclusiveBegin(), "inclusiveBegin"));
            deleteFilter.setExclusiveEnd(normaliseDeleteBound(ddp, ddp.getExclusiveEnd(), "exclusiveEnd"));
            deleteData.getItems().add(deleteFilter);

            log.debug("Sending delete data message to producer.");
            allDatapointProducer.send(DatapointBinaryConverter.toBinary(deleteData));

        }
        new DataWrapper<>();
    }

    /**
     * Parses one delete-window bound, accepting either ISO-8601 (with offset or zone) or epoch
     * millis — the same two forms every other timestamp on the API takes — and returns it as an
     * ISO-8601 UTC string. A null bound stays null and means "unbounded on that side".
     *
     * @throws BadRequestException if the value is neither form, so the caller gets a 400 instead of
     *                             a 204 followed by a message the consumer can never apply.
     */
    private static String normaliseDeleteBound(DeleteDatapoint ddp, String bound, String fieldName) {
        if (bound == null) {
            return null;
        }
        try {
            return DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(bound)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException | NumberFormatException e) {
            var badRequestError = new BadRequestError();
            badRequestError.setMessage(("'%s' is not a valid %s. Use ISO-8601 (2026-01-01T00:00:00Z) "
                    + "or epoch milliseconds (1767225600000).").formatted(bound, fieldName));
            badRequestError.setFields(List.of(Map.of(
                    fieldName, bound,
                    "externalId", String.valueOf(ddp.getExternalId()))));
            ResponseError<BadRequestError> errors = new ResponseError<>();
            errors.setError(badRequestError);
            throw new BadRequestException(errors);
        }
    }

    @Transactional(readOnly = true)
    public DataWrapper<DataCollection<DatapointDTO>> fetchLatestDatapoint(DataWrapper<IdCollection> apiReqData) throws JsonProcessingException {
        List<TimeseriesEntity> timeseriesList = readByIdCollection(apiReqData.getItems());
        DataWrapper<DataCollection<DatapointDTO>> results = new DataWrapper<>();
        for(TimeseriesEntity ts : timeseriesList){
            var latestDatapoint = valkeyService.fetchLatestDatapoint(ts.getExternalId());
            if(latestDatapoint == null){
                latestDatapoint = clickHouseDatapointService.findLatestDatapointInClickhouse(ts);
            }
            if(latestDatapoint.getValue() != null){
                ZonedDateTime latestTime = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(latestDatapoint.getTimestamp());
                int valueTypeId = ts.getValueType().getId();

                DataCollection<DatapointDTO> data = new DataCollection<>();
                switch (valueTypeId){
                    case 1: {
                        // BIGINT
                        var v = Long.parseLong(latestDatapoint.getValue());
                        var datapoint = new DatapointBigIntDTO(latestTime, v);
                        data.setDatapoints(List.of(datapoint));
                        break;
                    }
                    case 2: {
                        // FLOAT
                        var v = Double.parseDouble(latestDatapoint.getValue());
                        var datapoint = new DatapointFloatDTO(latestTime, v);
                        data.setDatapoints(List.of(datapoint));
                        break;
                    }
                    case 3: {
                        // NUMERIC
                        var v = new BigDecimal(latestDatapoint.getValue());
                        var datapoint = new DatapointNumericDTO(latestTime, v);
                        data.setDatapoints(List.of(datapoint));
                        break;
                    }
                    case 4: {
                        // TEXT
                        var v = latestDatapoint.getValue();
                        var datapoint = new DatapointTextDTO(latestTime, v);
                        data.setDatapoints(List.of(datapoint));
                        break;
                    }
                    case 5: {
                        // DECIMAL32 — read back as an exact decimal, same shape as NUMERIC
                        var v = new BigDecimal(latestDatapoint.getValue());
                        var datapoint = new DatapointNumericDTO(latestTime, v);
                        data.setDatapoints(List.of(datapoint));
                        break;
                    }
                    case 6: {
                        // MIXED — read back coalesced to a string (number or text)
                        var v = latestDatapoint.getValue();
                        var datapoint = new DatapointTextDTO(latestTime, v);
                        data.setDatapoints(List.of(datapoint));
                        break;
                    }
                    case 7: {
                        // FLOAT32 — read back as a float, same shape as FLOAT
                        var v = Double.parseDouble(latestDatapoint.getValue());
                        var datapoint = new DatapointFloatDTO(latestTime, v);
                        data.setDatapoints(List.of(datapoint));
                        break;
                    }
                }
                data.setId(ts.getId());
                data.setExternalId(ts.getExternalId());
                results.getItems().add(data);
            }
        }
        return results;
    }

    @Transactional(rollbackFor = Exception.class)
    public DataWrapper<Timeseries> updateTimeseries(DataWrapper<UpdateTimeseries> apiReqData)
            throws DuplicateDataException, PulsarClientException, ConstraintViolationException {

        // Validate Entities
        apiReqData.getItems().forEach( ts -> {
            if(ts.getExternalId() == null && !ts.hasId()){
                ResponseError<BadRequestError> errors = new ResponseError<>();
                var badRequestError = new BadRequestError();
                badRequestError.setMessage("Timeseries id or externalId is required.");
                badRequestError.setFields(List.of(Map.of("id", "null", "externalId", "null")));
                errors.setError(badRequestError);
                throw new BadRequestException(errors);
            }
            if(!ts.getUpdate().validateUpdateFields()){
                ResponseError<BadRequestError> errors = new ResponseError<>();
                ts.getUpdate().getErrors().forEach( error -> {
                    errors.getError().addFieldError(error.getObjectName(), error.getDefaultMessage());
                });
                throw new BadRequestException(errors);
            }
        });

        DataWrapper<Timeseries> updatedTimeseries = updateTimeseries(apiReqData.getItems());

        // When updating in neo4j in the consumer, we need the id
        for(var ts : updatedTimeseries.getItems()){
            for(var updateTs : apiReqData.getItems()){
                if( ts.getExternalId().equals(updateTs.getExternalId())){
                    updateTs.setId(ts.getId());
                }
            }
        }

        var msg = new ResourceCudMessage(EventAction.UPDATE, EventObject.TIMESERIES, TenantContext.getTenantId());
        msg.setUpdateTimeseries(apiReqData.getItems().stream().toList());
        applicationEventPublisher.publishEvent(new ResourceCudPublishEvent(msg));

        return updatedTimeseries;
    }

    @Transactional
    protected DataWrapper<Timeseries> updateTimeseries(Collection<UpdateTimeseries> timeseries){

        Set<Long> tsIdList = timeseries.stream().map(UpdateTimeseries::getId).collect(Collectors.toSet());
        Set<String> tsExternalIdList = timeseries.stream().map(UpdateTimeseries::getExternalId).collect(Collectors.toSet());

        // Two renames to the same externalId in ONE batch would each pass the per-item collision
        // check (neither is in the DB yet) and only die on the unique constraint — catch it here.
        Set<String> renameTargets = new HashSet<>();
        List<Map<String, String>> duplicatedRenames = new ArrayList<>();
        for (UpdateTimeseries ut : timeseries) {
            String target = ut.getUpdate() != null ? ut.getUpdate().getExternalId().getSet() : null;
            if (target != null && !renameTargets.add(target)) {
                duplicatedRenames.add(Map.of("externalId", target));
            }
        }
        if (!duplicatedRenames.isEmpty()) {
            throw duplicateExternalIdException(duplicatedRenames);
        }

        Collection<TimeseriesEntity> dbTimeseries = timeseriesRepository.findAllByIdOrExternalId(tsIdList, tsExternalIdList);

        // Judge the whole batch BEFORE the loop below mutates anything. The loop writes the new
        // external id straight onto the managed entity, after which Hibernate may auto-flush ahead
        // of the guard's own query and persist a value the policy was about to reject.
        List<PolicyFinding> policyWarnings = policyEnforcement.check(namingCandidatesForUpdate(dbTimeseries, timeseries));

        dbTimeseries.forEach( dbTs -> {
            UpdateTimeseries updateData = matchUpdateFor(dbTs, timeseries);
            if(updateData != null){
                // Must be able to write the timeseries' current dataset before mutating it.
                dataSecurity.assertCanWrite(dbTs);
                TimeseriesFields fields = updateData.getUpdate();
                dbTs.setLastUpdated(ZonedDateTime.now());

                // Update name field
                if(fields.getName().getSet() != null){
                    dbTs.setName(fields.getName().getSet());
                }

                // Update externalId
                if(fields.getExternalId().getSet() != null){
                    String newExternalId = fields.getExternalId().getSet();
                    // A rename must not collide with another timeseries' (unique) externalId — reject
                    // with a 409 instead of letting the DB unique constraint surface as a 500 on save.
                    if(!newExternalId.equals(dbTs.getExternalId())){
                        // The unique constraint spans the whole node table, so the collision check
                        // must too — a rename clashing with a resource/dataset externalId would
                        // otherwise pass here and die on the constraint as a 500.
                        NameAndExternalId clash = nodeRepository.findByExternalIdHash(
                                ExternalIds.hash(newExternalId), NameAndExternalId.class);
                        if(clash != null && !Objects.equals(clash.getId(), dbTs.getId())){
                            throw duplicateExternalIdException(List.of(Map.of("externalId", newExternalId)));
                        }
                    }
                    dbTs.setExternalId(newExternalId);
                }

                /*
                 * Update metadata
                 * If key found, update metadata value in existing entry,
                 * If key not found, add entry
                 * If remove, delete metadata entry
                 */
                if(fields.getMetadata().getSet() != null){
                    dbTs.setMetadata( fields.getMetadata().getSet()  );

                }
                if(fields.getMetadata().getAdd() != null){
                    Map<String, String> meta = new HashMap<>(dbTs.getMetadata());
                    meta.putAll(fields.getMetadata().getAdd());
                    dbTs.setMetadata(meta);
                }
                if(fields.getMetadata().getRemove() != null){
                    Map<String, String> meta = new HashMap<>(dbTs.getMetadata());
                    meta.keySet().removeAll(fields.getMetadata().getRemove());
                    dbTs.setMetadata(meta);
                }

                // Update unit field
                if(fields.getUnit().getSet() != null){
                    dbTs.setUnit(fields.getUnit().getSet());
                }
                if(fields.getUnit().getSetNull()){
                    dbTs.setUnit(null);
                }

                // Update unit external id
                if(fields.getUnitExternalId().getSet() != null){
                    dbTs.setUnitExternalId(fields.getUnitExternalId().getSet());
                }
                if(fields.getUnitExternalId().getSetNull()){
                    dbTs.setUnitExternalId(null);
                }

                // Update description field
                if(fields.getDescription().getSet() != null){
                    dbTs.setDescription(fields.getDescription().getSet());
                }
                if(fields.getDescription().getSetNull()){
                    dbTs.setDescription(null);
                }

                // Update source field (common to all node types; graph side already handles it)
                if(fields.getSource().getSet() != null){
                    dbTs.setSource(fields.getSource().getSet());
                }
                if(fields.getSource().getSetNull()){
                    dbTs.setSource(null);
                }

                // Update securityCategories field
                if(fields.getSecurityCategories().getSet() != null){

                    Set<Integer> scIdList = fields.getSecurityCategories().getSet()
                            .stream()
                            .mapToInt(Long::intValue)
                            .boxed()
                            .collect(Collectors.toCollection(TreeSet::new));
                    dbTs.setSecurityCategories(scIdList);
                }

                if(fields.getSecurityCategories().getAdd() != null){
                    Collection<Integer> addList = fields.getSecurityCategories().getAdd()
                            .stream()
                            .mapToInt(Long::intValue)
                            .boxed()
                            .collect(Collectors.toCollection(TreeSet::new));
                    dbTs.getSecurityCategories().addAll(addList);
                }

                if(fields.getSecurityCategories().getRemove() != null){
                    Collection<Integer> removeList = fields.getSecurityCategories().getRemove()
                            .stream()
                            .mapToInt(Long::intValue)
                            .boxed()
                            .collect(Collectors.toCollection(TreeSet::new));
                    dbTs.getSecurityCategories().removeAll(removeList);
                }

                // Update dataset id field
                if(fields.getDataSetId().getSet() != null){
                    long datasetId = fields.getDataSetId().getSet();
                    // Moving a timeseries into a dataset also requires write access to the target.
                    dataSecurity.assertCanWriteDataSet(datasetId);
                    DatasetEntity ds = datasetEntityRepository.getReferenceById(datasetId);
                    dbTs.setDataSet(ds);
                }
                if(fields.getDataSetId().getSetNull()){
                    dbTs.setDataSet(null);
                }

                var updatedTimeseries = nodeRepository.save(dbTs);
                log.debug("Updated timeseries: {}", updatedTimeseries);
            }

        });

        recordPolicyWarnings(policyWarnings, dbTimeseries);

        DataWrapper<Timeseries> data = new DataWrapper<>();
        data.setItems(TimeseriesTransformer.from(dbTimeseries));
        data.setWarnings(policyWarnings.stream().map(PolicyWarning::from).toList());
        return data;
    }

    /**
     * Pair a loaded entity with the update form that targets it, by id or by external id.
     *
     * <p>Extracted so the naming check and the mutation loop pair them the same way. They must: a
     * mismatch would judge one entity's rename and apply another's.
     */
    private static UpdateTimeseries matchUpdateFor(TimeseriesEntity dbTs, Collection<UpdateTimeseries> timeseries) {
        return timeseries.stream()
                .filter(it ->
                        (dbTs.getId() != null && Objects.equals(dbTs.getId(), it.getId())) ||
                        (dbTs.getExternalId() != null && Objects.equals(dbTs.getExternalId(), it.getExternalId()))
                )
                .findFirst()
                .orElse(null);
    }

    /**
     * Reduce an update batch to naming-policy candidates, keeping only the items whose external id
     * actually changes.
     *
     * <p>Carrying the previous value is what enables that, and the rule matters: without it,
     * tightening a policy would make every pre-existing timeseries unupdatable, and editing a
     * description on a legacy series would fail on a naming rule the caller never touched.
     */
    private static List<PolicyCandidate> namingCandidatesForUpdate(Collection<TimeseriesEntity> dbTimeseries,
                                                                   Collection<UpdateTimeseries> timeseries) {
        List<PolicyCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (TimeseriesEntity dbTs : dbTimeseries) {
            UpdateTimeseries updateData = matchUpdateFor(dbTs, timeseries);
            String newExternalId = (updateData == null || updateData.getUpdate() == null)
                    ? null
                    : updateData.getUpdate().getExternalId().getSet();
            if (newExternalId != null) {
                // The incoming name if this request renames it, else the stored one.
                String newName = updateData.getUpdate().getName().getSet();
                candidates.add(PolicyCandidate.forUpdate(
                        index, newExternalId,
                        newName != null ? newName : dbTs.getName(),
                        dbTs.getDataSet() == null ? null : dbTs.getDataSet().getId(),
                        dbTs.getId(), dbTs.getExternalId()));
            }
            index++;
        }
        return candidates;
    }
    /**
     * Resolve timeseries by id/externalId, narrowed in SQL to the datasets the caller may read.
     * Admins / read-all callers skip the filter entirely; a caller with no readable datasets gets
     * an empty result without hitting the database. Used by the batch read endpoints so lookups
     * silently omit datasets the caller can't see (matching the "missing items are left out"
     * contract) rather than failing the whole request.
     */
    public List<TimeseriesEntity> readByIdCollection(Collection<IdCollection> coll) {
        if (dataSecurity.hasReadAccessToEverything()) {
            return timeseriesRepository.findAllByIdCollection(coll);
        }
        Set<Long> allowed = dataSecurity.readableDataSetIds();
        if (allowed.isEmpty()) {
            return new ArrayList<>();
        }
        return timeseriesRepository.findAllByIdCollectionAndDataSetIdIn(coll, allowed);
    }

    /** List recent timeseries, narrowed in SQL to the datasets the caller may read. */
    public List<TimeseriesEntity> readList(int maxResults) {
        if (dataSecurity.hasReadAccessToEverything()) {
            return timeseriesRepository.list(maxResults);
        }
        Set<Long> allowed = dataSecurity.readableDataSetIds();
        if (allowed.isEmpty()) {
            return new ArrayList<>();
        }
        return timeseriesRepository.list(maxResults, allowed);
    }

    /**
     * List recent timeseries belonging to the given dataset <em>or any dataset beneath it</em> in
     * the {@code BELONGS_TO} hierarchy, narrowed to the datasets the caller may read.
     *
     * <p>The hierarchy expansion and its ACL intersection are described on
     * {@link #visibleDatasetClosure}. A dataset id the caller cannot read simply drops out of the
     * closure — the result is what they are allowed to see, not an error, matching how
     * {@link #readList} narrows.
     */
    public List<TimeseriesEntity> readListForDataSet(long dataSetId, int maxResults) {
        Set<Long> visible = visibleDatasetClosure(dataSetId);
        if (visible.isEmpty()) {
            return new ArrayList<>();
        }
        return timeseriesRepository.list(maxResults, visible);
    }

    /**
     * The dataset's downward {@code BELONGS_TO} closure, narrowed to what the caller may read.
     * Empty means "nothing visible here" — callers short-circuit rather than querying with it.
     *
     * <p>Resolved through {@link DatasetClosureService}, the same component that expands access
     * grants, so {@code dataSetId=X} covers exactly the datasets a grant on X would. This used to
     * walk the Neo4j mirror instead: a second implementation of the same concept, over a store the
     * stateful consumer writes asynchronously, so a timeseries in a freshly created child dataset
     * was authorized correctly but missing from the filter until the mirror caught up.
     */
    private Set<Long> visibleDatasetClosure(long dataSetId) {
        return narrowToReadable(datasetClosureService.closureOf(Set.of(dataSetId)));
    }

    /**
     * The same closure for a filter's {@code dataSetId}, whose entries may name a data set by id
     * or by externalId. Empty means "nothing visible here", including the case where every
     * reference named a data set that does not exist — the caller asked to be narrowed to those,
     * and answering with everything would be the opposite of what they asked.
     */
    private Set<Long> visibleDatasetClosure(Collection<IdCollection> references) {
        return narrowToReadable(datasetClosureService.closureOfReferences(references));
    }

    private Set<Long> narrowToReadable(Set<Long> closure) {
        if (dataSecurity.hasReadAccessToEverything()) {
            return closure;
        }
        Set<Long> allowed = dataSecurity.readableDataSetIds();
        // A Set either way. It used to hand back the raw list for a read-all caller and a filtered
        // list otherwise, so the collection type depended on who was asking; it only ever feeds an
        // IN (...) clause, where order and duplicates are irrelevant.
        return closure.stream().filter(allowed::contains).collect(Collectors.toSet());
    }

    /**
     * Structured AND-combined timeseries filter: dataset (hierarchy-expanded, like
     * {@link #readListForDataSet}), unit, unitExternalId, and metadata key/value. Narrowed to the
     * caller's readable datasets in SQL, mirroring {@code ResourceService.filter}.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Timeseries> filter(TimeseriesRetreiver apiReqData) {
        DataWrapper<Timeseries> data = new DataWrapper<>();
        TimeseriesFilter filter = apiReqData.getFilter() != null
                ? apiReqData.getFilter()
                : new TimeseriesFilter();

        Collection<Long> dataSetIds = null; // null = no dataset restriction in SQL
        if (filter.getDataSetId() != null) {
            dataSetIds = visibleDatasetClosure(filter.getDataSetId());
            if (dataSetIds.isEmpty()) {
                return data;
            }
        } else if (!dataSecurity.hasReadAccessToEverything()) {
            Set<Long> allowed = dataSecurity.readableDataSetIds();
            if (allowed.isEmpty()) {
                return data;
            }
            dataSetIds = allowed;
        }

        NodeSort sort = NodeSort.resolve(apiReqData.getSort());
        PageCursor cursor = NodePaging.validated(apiReqData.getCursor(), sort);

        List<TimeseriesEntity> entities = timeseriesRepository.filter(
                apiReqData.getLimit(), dataSetIds, filter, sort, cursor);
        List<Long> nodeIds = entities.stream().map(TimeseriesEntity::getId).collect(Collectors.toList());
        Collection<EdgeEntity> edgeEntities = edgeRepository.findAllByEndIn(nodeIds, EdgeEntity.class);
        data.setItems(TimeseriesTransformer.from(entities, edgeEntities));
        data.setNextCursor(NodePaging.nextCursor(entities, apiReqData.getLimit(), sort));
        return data;
    }

    /**
     * Fetch a single timeseries by its numeric id.
     *
     * <p>Mirrors {@code ResourceService.get}, including the existence-hiding rule: a timeseries the
     * caller may not read is reported as missing rather than forbidden, so a 403 can never confirm
     * that a hidden row exists. Timeseries and datasets were the two node types with no single-item
     * GET — callers had to POST a one-element {@code /byids} wrapper.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Timeseries> get(Long id) {
        TimeseriesEntity entity = timeseriesRepository.findById(id).orElseThrow(() ->
                new ObjectNotFoundException("Timeseries with id: " + id + " Not found!"));

        if (!dataSecurity.hasReadPermissionToDataSet(entity)) {
            throw new ObjectNotFoundException("Timeseries with id: " + id + " Not found!");
        }

        Collection<EdgeEntity> edgeEntities =
                edgeRepository.findAllByStartInOrEndIn(List.of(id), List.of(id), EdgeEntity.class);
        var data = new DataWrapper<Timeseries>();
        data.setItems(TimeseriesTransformer.from(List.of(entity), edgeEntities));
        return data;
    }

    @Transactional
    public DataWrapper<Timeseries> byids(Collection<IdCollection> id_inputs){
    var tsEntityList = readByIdCollection(id_inputs);
    List<Long> nodeIds = tsEntityList.stream().map(TimeseriesEntity::getId).collect(Collectors.toList());
    Collection<EdgeEntity> edgeEntities = edgeRepository.findAllByStartInOrEndIn(nodeIds,nodeIds, EdgeEntity.class);
    Collection<Timeseries> tsList = TimeseriesTransformer.from(tsEntityList, edgeEntities);
    var data = new DataWrapper<Timeseries>();
        data.setItems(tsList);
        return data;
    }

}
