// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.api.controllers.errors.*;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.messaging.events.EventCudPublishEvent;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.clickhouse.ClickHouseService;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.dto.NameAndExternalId;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.models.events.EventQueryResult;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.models.events.LeanEvent;
import ai.intellistream.datahub.models.validation.EventFields;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.KVRocksService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@AllArgsConstructor
public class EventService {

    private final NodeRepository nodeRepository;
    private final ClickHouseEventService clickHouseEventService;
    private final KVRocksService kvRocksService;
    private final Validator validator;
    private final ApplicationEventPublisher applicationEventPublisher;
    // Used only by delete(), which sends synchronously instead of going through the transactional
    // AfterCommitMessagePublisher path (see the send call in delete() for the rationale).
    private final Producer<EventCudMessage> eventMessageProducer;
    private final DataSecurity dataSecurity;
    private final EventDimensionRepository eventDimensionRepository;
    private final DataSetRepository dataSetRepository;
    /** The one authority for "which data sets are beneath this one" — shared with the ACL. */
    private final DatasetClosureService datasetClosureService;
    private final IngestQuotaService ingestQuota;

    /**
     * The dataset ids the caller may read, or {@code null} when the caller may read every dataset
     * (so the ClickHouse query applies no dataset restriction). An empty set means the caller has
     * no readable datasets — the ClickHouse layer then matches nothing.
     */
    private Collection<Long> readAclOrNull() {
        return dataSecurity.hasReadAccessToEverything() ? null : dataSecurity.readableDataSetIds();
    }

    /**
     * Resolves the {@code dataSetId} references to plain ids, so the storage layer only ever deals
     * in ids. References given by id pass straight through; those given by externalId are looked up
     * in one batch on the indexed {@code external_id_hash}, the way nodes are looked up everywhere
     * else.
     *
     * <p>Each resolved data set is then expanded through {@link DatasetClosureService} to the data
     * sets beneath it, so {@code dataSetId} covers a hierarchy rather than a literal list.
     *
     * <p>An externalId naming no data set contributes nothing — but "nothing" is not "no filter". A
     * list of only unknown names leaves an <em>empty</em> id list behind, which the ClickHouse layer
     * reads as "match nothing". That is deliberate: the caller asked to be narrowed to those data
     * sets, and answering with every event they can read would be the opposite of what they asked.
     *
     * <p>Normalises in place, rewriting the list as id-only references; a filter that does not
     * restrict by data set is left alone, so null stays null. The retriever is a per-request
     * {@code @RequestBody} DTO, never shared.
     */
    private void resolveDataSetIds(EventFilter filter) {
        if (filter == null || filter.getDataSetId() == null) {
            return;
        }
        // Expand each data set to everything beneath it, the same closure a grant on it covers.
        // Events used to match the listed ids exactly, so filtering on a parent returned none of
        // its children's events while the same filter against timeseries returned them — one
        // concept, two answers. Empty stays empty: it means "match nothing", not "no restriction".
        Set<Long> expanded = datasetClosureService.closureOfReferences(filter.getDataSetId());
        filter.setDataSetId(expanded.stream().map(IdCollection::createFromId).toList());
    }

    public DataWrapper<EventModel> findAllByIdAndExternalId(Collection<UUIDAndExternalIdCollection> items) {
        DataWrapper<EventModel> data = new DataWrapper<>();

        Set<UUID> idList = new HashSet<>();
        Set<String> externalIds = new HashSet<>();
        for(UUIDAndExternalIdCollection item : items){
            if(item.getId() != null){
                idList.add(item.getId());
            }
            else if(item.getExternalId() != null){
                externalIds.add(item.getExternalId());
            }
        }

        if(!externalIds.isEmpty()){
            try{
                // Find ids based externalIds
                List<UUID> remainingIds = kvRocksService.findEventIdsByExternalIdCollection(externalIds);
                idList.addAll(remainingIds);
            } catch (Exception e){
                throw new RuntimeException(e);
            }
        }

        data.setItems( clickHouseEventService.findAllById(idList, TenantContext.getTenantId(), EventModel.class, readAclOrNull()) );
        return data;
    }

    public DataWrapper<EventModel> filter(EventRetreiver retreiver) {
        DataWrapper<EventModel> data = new DataWrapper<>();
        ClickHouseEventService.EventSortSpec sort = ClickHouseEventService.resolveSort(retreiver.getSort());
        assertCursorIsUsable(retreiver.getCursor(), sort);

        resolveDataSetIds(retreiver.getFilter());
        // Use Clickhouse for filtering, narrowed in SQL to the caller's readable datasets.
        List<EventModel> events = clickHouseEventService.filter(retreiver, readAclOrNull());
        data.setItems(events);
        data.setNextCursor(nextCursor(events, retreiver.getLimit(), sort));
        return data;
    }

    /**
     * Reject a cursor that cannot mean what the caller is asking for, rather than answering it.
     *
     * <p>A cursor is a position in one specific order. Continuing it under a different sort asks
     * "everything after 14:32" of a sequence that is no longer in time order, which returns a page
     * that is silently wrong — the failure mode a cursor exists to prevent. The previous behaviour
     * was to ignore {@code sort} whenever a cursor was present, which only looked harmless while
     * there was exactly one possible order.
     */
    private void assertCursorIsUsable(String rawCursor, ClickHouseEventService.EventSortSpec sort) {
        PageCursor cursor = PageCursor.decode(rawCursor);
        if (cursor == null) {
            return; // none supplied: the start of a walk, not an error in one
        }
        if (!ClickHouseEventService.canReadBoundary(sort, cursor.value())) {
            // Well-formed encoding, unusable contents — forged or truncated. Rejected like any
            // other unreadable cursor rather than restarting, which would loop a paging client.
            // Not quoting the value back; see NodePaging.validated.
            throw new MalformedCursorException(
                    "The cursor's position cannot be read as a %s. ".formatted(sort.property())
                    + "Send back a nextCursor exactly as it was returned, or omit it to start again.");
        }
        if (!cursor.matches(sort.property(), sort.descending())) {
            throw new MalformedCursorException(
                    "This cursor was produced by a different sort (%s %s) than the one requested (%s %s). "
                            .formatted(cursor.property(), cursor.descending() ? "desc" : "asc",
                                    sort.property(), sort.descending() ? "desc" : "asc")
                            + "Send the cursor with the sort it came from, or start a new walk without it.");
        }
    }

    /**
     * The cursor for the page after this one, or null when there is not one.
     *
     * <p>A short page means the end of the result set, so no cursor: "keep going while nextCursor
     * is present" is then the whole client loop, with no separate end-of-data signal to get wrong.
     * A full page may still be the last one, in which case the caller makes one extra request that
     * comes back empty — the cost of not counting the rows twice.
     */
    private String nextCursor(List<EventModel> events, int limit,
                              ClickHouseEventService.EventSortSpec sort) {
        if (events.isEmpty() || events.size() < limit) {
            return null;
        }
        EventModel last = events.get(events.size() - 1);
        // A null value is a position, not a missing one: it addresses the null block. Returning no
        // cursor for it used to end the walk after one page, losing every row beyond it.
        return new PageCursor(sort.property(), sort.descending(),
                cursorValue(last, sort.property()), last.getId()).encode();
    }

    /**
     * The last row's value for the sorted property, in the form {@link PageCursor} carries.
     *
     * <p>Timestamps go in as epoch millis rather than as whatever {@code toString} produces. The
     * getters here return {@link ZonedDateTime} even though the field behind them is a {@code Long}
     * — the DTOs double as Avro payloads, so they store millis and present ISO-8601 — and an ISO
     * string is not what the query layer parses the boundary back out of. Epoch millis also survive
     * a round trip through any client without a timezone or precision question attached.
     */
    private static String cursorValue(EventModel event, String property) {
        return switch (property) {
            case "eventTime" -> epochMillis(event.getEventTime());
            case "createdTime" -> epochMillis(event.getCreatedTime());
            case "lastUpdatedTime" -> epochMillis(event.getLastUpdatedTime());
            case "externalId" -> event.getExternalId();
            case "type" -> event.getType();
            case "subType" -> event.getSubType();
            case "status" -> event.getStatus();
            case "source" -> event.getSource();
            case "dataSetId" -> event.getDataSetId() == null ? null : String.valueOf(event.getDataSetId());
            default -> null;
        };
    }

    private static String epochMillis(ZonedDateTime time) {
        return time == null ? null : String.valueOf(time.toInstant().toEpochMilli());
    }

    /**
     * Backs the {@code event_filter} MCP tool. With a {@code groupBy} it returns
     * {@code (value, count)} buckets (aggregate); without one it returns up to the retriever's
     * limit of {@link LeanEvent}s (drill-down). Either way the query is narrowed in SQL to the
     * caller's readable datasets, like {@link #filter}.
     */
    public EventQueryResult queryEvents(EventRetreiver retreiver, String groupBy) {
        resolveDataSetIds(retreiver.getFilter());
        Collection<Long> acl = readAclOrNull();
        EventQueryResult result = new EventQueryResult();

        if (groupBy != null) {
            List<EventQueryResult.Bucket> buckets = clickHouseEventService.aggregate(retreiver, groupBy, acl);
            result.setGroupedBy(groupBy);
            result.setBuckets(buckets);
            result.setTotal(buckets.stream().mapToLong(EventQueryResult.Bucket::count).sum());
        } else {
            List<EventModel> events = clickHouseEventService.filter(retreiver, acl);
            int limit = retreiver.getLimit();
            result.setEvents(events.stream().map(EventService::toLean).toList());
            result.setReturned(events.size());
            result.setLimit(limit);
            // events come back capped at 'limit'; hitting the cap means more may match.
            result.setTruncated(events.size() >= limit);
        }
        return result;
    }

    /**
     * Collapse a full {@link EventModel} to the compact {@link LeanEvent} the MCP tools return
     * (drops the UUID id, audit timestamps, source, status, and empties). Shared by
     * {@code event_filter}'s drill-down and {@code event_search}.
     */
    public static LeanEvent toLean(EventModel e) {
        Map<String, String> metadata = (e.getMetadata() == null || e.getMetadata().isEmpty()) ? null : e.getMetadata();
        // External ids only: the lean view is for an LLM, and the internal id carries no meaning there.
        List<String> related = (e.getRelatedResources() == null || e.getRelatedResources().isEmpty())
                ? null
                : e.getRelatedResources().stream()
                        .map(IdCollection::getExternalId)
                        .filter(Objects::nonNull)
                        .toList();
        String eventTime = e.getEventTime() == null ? null : e.getEventTime().toString();
        return new LeanEvent(
                e.getExternalId(), e.getType(), e.getSubType(), eventTime,
                e.getDescription(), e.getDataSetId(), metadata, related);
    }

    /**
     * Full-text search over events, optionally narrowed by an {@link EventFilter}.
     *
     * <p>Same contract as the three node searches: the phrase decides the candidates, the filter
     * only removes some of them, {@code limit} caps what survives. The filter was accepted and
     * dropped on the floor here — every criterion on it silently did nothing.
     *
     * <p>Unlike those three the narrowing happens in one ClickHouse query rather than as a second
     * pass, so there is no candidate ceiling to raise: the text match, the filter and the dataset
     * ACL are ANDed together and {@code limit} applies to the result.
     */
    public DataWrapper<EventModel> search(SearchBody<EventFilter> form) {
        DataWrapper<EventModel> out = new DataWrapper<>();
        String query = (form != null && form.getSearch() != null) ? form.getSearch().getQuery() : null;
        int limit = (form != null) ? Math.max(1, form.getLimit()) : 100;
        EventFilter filter = (form != null) ? form.getFilter() : null;
        // Expand dataSetId to its BELONGS_TO closure, exactly as POST /events/filter does — one
        // filter field cannot mean a hierarchy on one endpoint and a literal list on the other.
        resolveDataSetIds(filter);
        out.setItems(clickHouseEventService.search(query, limit, readAclOrNull(), filter));
        return out;
    }

    @Transactional
    public DataWrapper<EventModel> create(DataWrapper<EventModel> apiReqData)
            throws PulsarClientException, DuplicateDataException
    {
        Set<ConstraintViolation<DataWrapper<EventModel>>> errors = validator.validate(apiReqData);
        if (!errors.isEmpty()) {
            throw new ConstraintViolationException(errors);
        }

        var dw = new DataWrapper<EventModel>();

        List<EventModel> eventModels = apiReqData.getItems().stream().toList();
        if(eventModels.isEmpty()){
            return dw;
        }

        // Charged here rather than in a filter: event_create reaches this method directly.
        ingestQuota.checkAndRecord(IngestQuotaService.QuotaMetric.EVENTS, eventModels.size());

        Set<Long> dataSets = new HashSet<>();
        Set<String> externalIdList = new HashSet<>();
        Set<Long> resourceIds = new HashSet<>();
        Set<String> resourceExternalIds = new HashSet<>();
        eventModels.forEach( it -> {
            // Deny creating an event in a dataset the caller can't write (null dataset → requires
            // the write-all grant).
            dataSecurity.assertCanWriteDataSet(it.getDataSetId());
            // Honor a valid client-supplied id (lets clients dedup retries via the ReplacingMergeTree),
            // otherwise mint a time-ordered one. Must be a valid UUID: KVRocks/update paths parse it.
            if (!isValidUuid(it.getId())) {
                it.setId(IdGenerator.getRandomUUID7AsString());
            }
            if(it.getDataSetId() != null) {
                dataSets.add(it.getDataSetId());
            }
            externalIdList.add( it.getExternalId());
            collectRelatedReferences(it.getRelatedResources(), resourceIds, resourceExternalIds);
            ZonedDateTime now = ZonedDateTime.now();
            it.setCreatedTime(now);
            it.setLastUpdatedTime(now);
        });

        try{
            validateDataSets(dataSets);

            List<NameAndExternalId> resources = validateAndFetchResources(resourceIds, resourceExternalIds);
            resolveRelatedResources(eventModels, resources);
        } catch (BadRequestException | DuplicateDataException e){
            throw e;
        } catch (Exception e){
            throw new RuntimeException(e.getMessage(), e);
        }

        publishCreate(eventModels);

        dw.setItems(eventModels);
        return dw;
    }

    /**
     * Register the events in KVRocks and hand them to the consumers once the caller's transaction
     * commits. Shared by the request-driven {@link #create} and the platform-driven
     * {@link #createPlatformEvents}.
     */
    private void publishCreate(List<EventModel> eventModels) {
        try {
            // Create Events in Apache KVRocks first
            kvRocksService.saveEvents(eventModels);
        } catch (BadRequestException | DuplicateDataException e){
            throw e;
        } catch (Exception e){
            throw new RuntimeException(e.getMessage(), e);
        }

        var message = new EventCudMessage();
        message.setEvents(eventModels);
        message.setEventAction(EventAction.CREATE);
        message.setEventObject(EventObject.EVENT);
        message.setTenantId(TenantContext.getTenantId());

        // Don't send to Pulsar inline. Publishing a domain event defers the real
        // eventMessageProducer.send(...) until this @Transactional method commits — see
        // AfterCommitMessagePublisher#onEventCud (@TransactionalEventListener AFTER_COMMIT) for
        // the actual send. This closes the dual-write window where a message published before a
        // subsequent rollback would leave the downstream ClickHouse/KVRocks/Neo4j consumers
        // acting on state that was never committed to Postgres.
        applicationEventPublisher.publishEvent(new EventCudPublishEvent(message));
    }

    /**
     * Write events the platform itself raised, rather than events a caller submitted.
     *
     * <p>Used for observations the platform makes about a write while serving it — a policy finding
     * is the current case. These join the caller's transaction, so an event about a write that then
     * rolls back rolls back with it and never reaches the consumers.
     *
     * <p><b>No dataset write check, deliberately.</b> {@link #create} asks whether the caller may
     * write the target dataset, because there the caller chose it. Here the platform chose it — the
     * event is attached to whatever the entity under discussion belongs to — and the caller has
     * already been authorised for the write that triggered this. Running the check anyway would mean
     * a finding about an entity outside any dataset needs the write-all grant, so an ordinary user's
     * perfectly legal write would fail on the platform's own note about it. Read access is
     * unaffected: these are ordinary events, so the dataset ACL applies on the way out as usual.
     *
     * <p>Callers must supply ids and related resources themselves. The validation {@link #create}
     * does — that every related resource and dataset exists — is skipped, because these events are
     * built from entities the caller has just written and re-reading them to confirm they exist
     * would put queries on the write path to answer a question the transaction already settles. That
     * makes correctness the caller's job here in a way it is not on the public path.
     *
     * <p><b>Deliberately not {@code @Transactional}</b>, and this is load-bearing rather than an
     * omission. It does no PostgreSQL work of its own — KVRocks plus a deferred publish — so it
     * needs no transaction, and having one would actively break the caller. A {@code @Transactional}
     * method that throws marks the surrounding transaction rollback-only even if the caller catches
     * the exception, so a caller that means to treat a failed platform event as non-fatal would
     * still lose its own write to {@code UnexpectedRollbackException}. Without the annotation the
     * caller's {@code catch} does what it looks like it does. The caller's transaction is still what
     * the after-commit publish binds to; this only declines to start or join one of its own.
     */
    public void createPlatformEvents(List<EventModel> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        for (EventModel event : events) {
            if (!isValidUuid(event.getId())) {
                event.setId(IdGenerator.getRandomUUID7AsString());
            }
            event.setCreatedTime(now);
            event.setLastUpdatedTime(now);
        }
        publishCreate(events);
    }

    /** True if {@code id} is a non-blank, parseable UUID (the form KVRocks and the update path expect). */
    private static boolean isValidUuid(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Adds every id / externalId referenced by {@code related} to the lookup sets. */
    private static void collectRelatedReferences(Collection<IdCollection> related,
                                                 Set<Long> resourceIds, Set<String> resourceExternalIds) {
        if (related == null) { return; }
        for (IdCollection entry : related) {
            if (entry == null) { continue; }
            if (entry.getId() != null) { resourceIds.add(entry.getId()); }
            if (entry.getExternalId() != null) { resourceExternalIds.add(entry.getExternalId()); }
        }
    }

    /**
     * Normalizes each event's related-resource list against the resources already fetched from the
     * node table: fills in whichever side the caller omitted, rejects an entry whose id and
     * externalId name different resources, and dedupes.
     *
     * <p>The post-condition — every entry carries BOTH an id and an externalId — is what the
     * storage layer relies on to derive its three parallel columns as one index-aligned set. A
     * single list with one resolution point is why the two sides can no longer drift apart.
     */
    private void resolveRelatedResources(List<EventModel> eventModels, List<NameAndExternalId> resources) {
        Map<Long, NameAndExternalId> byId = new HashMap<>();
        Map<Long, NameAndExternalId> byExternalIdHash = new HashMap<>();
        for (NameAndExternalId resource : resources) {
            byId.putIfAbsent(resource.getId(), resource);
            byExternalIdHash.putIfAbsent(resource.getExternalIdHash(), resource);
        }

        for (EventModel em : eventModels) {
            em.setRelatedResources(resolveRelatedResources(em.getRelatedResources(), byId, byExternalIdHash));
        }
    }

    /** Resolves one list. Order is preserved and the first mention of a resource wins. */
    private List<IdCollection> resolveRelatedResources(Collection<IdCollection> related,
                                                       Map<Long, NameAndExternalId> byId,
                                                       Map<Long, NameAndExternalId> byExternalIdHash) {
        if (related == null || related.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, IdCollection> resolved = new LinkedHashMap<>();
        for (IdCollection entry : related) {
            NameAndExternalId resource = lookupRelatedResource(entry, byId, byExternalIdHash);
            IdCollection full = new IdCollection();
            full.setId(resource.getId());
            full.setExternalId(resource.getExternalId());
            resolved.putIfAbsent(resource.getId(), full);
        }
        return new ArrayList<>(resolved.values());
    }

    /**
     * Resolves one entry to the resource it names, or throws. When both sides are supplied they
     * must agree: silently unioning a mismatched pair is precisely how the old two-list model let
     * an event end up describing resources the caller never asked for.
     */
    private NameAndExternalId lookupRelatedResource(IdCollection entry,
                                                    Map<Long, NameAndExternalId> byId,
                                                    Map<Long, NameAndExternalId> byExternalIdHash) {
        if (entry == null || (entry.getId() == null && entry.getExternalId() == null)) {
            throw relatedResourceError("A related resource must have an id or an externalId.", entry);
        }

        NameAndExternalId byIdMatch = entry.getId() == null ? null : byId.get(entry.getId());
        NameAndExternalId byExternalIdMatch = entry.getExternalId() == null
                ? null : byExternalIdHash.get(entry.getExternalIdHash());

        if (entry.getId() != null && entry.getExternalId() != null) {
            if (byIdMatch == null || byExternalIdMatch == null || !byIdMatch.getId().equals(byExternalIdMatch.getId())) {
                throw relatedResourceError("The id and externalId refer to different resources.", entry);
            }
            return byIdMatch;
        }

        NameAndExternalId match = byIdMatch != null ? byIdMatch : byExternalIdMatch;
        if (match == null) {
            // validateAndFetchResources already rejects unknown references; this is defence in depth.
            throw relatedResourceError("No such resource.", entry);
        }
        return match;
    }

    private BadRequestException relatedResourceError(String message, IdCollection entry) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        var error = new BadRequestError();
        error.setMessage(message);
        Map<String, String> field = new LinkedHashMap<>();
        if (entry != null && entry.getId() != null) { field.put("resourceId", String.valueOf(entry.getId())); }
        if (entry != null && entry.getExternalId() != null) { field.put("resourceExternalId", entry.getExternalId()); }
        error.getFields().add(field);
        errors.setError(error);
        return new BadRequestException(errors);
    }

    @Transactional(readOnly = true)
    protected List<NameAndExternalId> validateAndFetchResources(Set<Long> resourceIds, Set<String> resourceExternalIds) {

        Set<NameAndExternalId> resources = new HashSet<>();

        List<NameAndExternalId> instancesFound = nodeRepository.findAllByIdIn(resourceIds, NameAndExternalId.class);
        if(instancesFound.size() != resourceIds.size()){
            ResponseError<BadRequestError> errors = new ResponseError<>();
            var de = new BadRequestError();
            de.setMessage("No resource with following id exists!");

            for (Long id : resourceIds) {
                if (instancesFound.stream().noneMatch(instance -> instance.getId().equals(id))) {
                    de.getFields().add(Map.of("resourceId", String.valueOf(id)));
                }
            }

            errors.setError(de);
            throw new BadRequestException(errors);
        }

        List<Long> externalIdHashes = resourceExternalIds.stream().map(ExternalIds::hash).toList();
        List<NameAndExternalId> results = nodeRepository.findAllByExternalIdHashIn(externalIdHashes, NameAndExternalId.class);
        if(results.size() != resourceExternalIds.size()){
            ResponseError<BadRequestError> errors = new ResponseError<>();
            var de = new BadRequestError();
            de.setMessage("No resource with following external id exists!");

            for (Long id : externalIdHashes) {
                if (results.stream().noneMatch(instance -> instance.getExternalIdHash().equals(id))) {
                    de.getFields().add(Map.of("resourceExternalId", String.valueOf(id)));
                }
            }

            errors.setError(de);
            throw new BadRequestException(errors);
        }

        resources.addAll(instancesFound);
        resources.addAll(results);

        return resources.stream().toList();
    }

    @Transactional(readOnly = true)
    protected boolean validateDataSets(Set<Long> dataSets) {
        List<Long> instancesFound = nodeRepository.findAllByIdAsIdList(dataSets);
        if(instancesFound.size() != dataSets.size()){
            ResponseError<BadRequestError> errors = new ResponseError<>();
            var de = new BadRequestError();
            de.setMessage("No dataset with following id exists!");

            for(Long dataSetId : dataSets){
                if(!instancesFound.contains(dataSetId)){
                    de.getFields().add(Map.of("dataSet", String.valueOf(dataSetId)));
                }
            }

            errors.setError(de);
            throw new BadRequestException(errors);
        }
        return true;
    }
    @Transactional
    public DataWrapper<EventModel> update(DataWrapper<UpdateEventForm> apiReqData) throws PulsarClientException {
        Set<UUID> idList = new HashSet<>();
        Set<String> hashList = new HashSet<>();

        // Create id and external id collections
        for(UpdateEventForm r : apiReqData.getItems()){
            if(r.getId() != null){
                idList.add(r.getId());
            }
            else if(r.getExternalId() != null){
                hashList.add( r.getExternalId() );
            }
        }

        if(!hashList.isEmpty()){
            // Find ids based externalIds
            try{
                List<UUID> remainingIds = kvRocksService.findEventIdsByExternalIdCollection(hashList);
                idList.addAll(remainingIds);
            } catch (Exception e){
                throw new RuntimeException(e.getMessage());
            }
        }

        // Find all events based on id and external id
        List<EventModel> events = clickHouseEventService.findAllById(idList, TenantContext.getTenantId(), EventModel.class);

        // Resolve every related resource the request could touch — the ones already on the stored
        // events plus the set/add/remove entries — in one lookup, before applying any patch. The
        // merge below then keys on the resolved node id, so `remove: [{id: 34}]` drops the stored
        // entry regardless of which side the caller named it by.
        Set<Long> resourceIds = new HashSet<>();
        Set<String> resourceExternalIds = new HashSet<>();
        events.forEach(it -> collectRelatedReferences(it.getRelatedResources(), resourceIds, resourceExternalIds));
        apiReqData.getItems().forEach(it -> {
            if (it.getUpdate() == null) { return; }
            var relatedResourceUpdate = it.getUpdate().getRelatedResources();
            if (relatedResourceUpdate == null) { return; }
            collectRelatedReferences(relatedResourceUpdate.getSet(), resourceIds, resourceExternalIds);
            collectRelatedReferences(relatedResourceUpdate.getAdd(), resourceIds, resourceExternalIds);
            collectRelatedReferences(relatedResourceUpdate.getRemove(), resourceIds, resourceExternalIds);
        });
        List<NameAndExternalId> resources = validateAndFetchResources(resourceIds, resourceExternalIds);
        Map<Long, NameAndExternalId> resourcesById = new HashMap<>();
        Map<Long, NameAndExternalId> resourcesByExternalIdHash = new HashMap<>();
        for (NameAndExternalId resource : resources) {
            resourcesById.putIfAbsent(resource.getId(), resource);
            resourcesByExternalIdHash.putIfAbsent(resource.getExternalIdHash(), resource);
        }

        // Moving an event into a dataset has to prove the dataset is real, exactly as create does.
        // Only the ACL check ran here before, and it answers a different question: a caller holding
        // a write-everything grant passes it for any id at all, including one no dataset has. The
        // event was then stored pointing at a dataset that does not exist — invisible to a
        // dataset-scoped read, and never repaired, because nothing downstream re-checks it.
        Set<Long> targetDataSets = apiReqData.getItems().stream()
                .map(UpdateEventForm::getUpdate)
                .filter(Objects::nonNull)
                .map(it -> it.getDataSetId().getSet())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if(!targetDataSets.isEmpty()){
            validateDataSets(targetDataSets);
        }

        // Collection that contains new External Ids, if some already exists, deny update.
        // If it contains duplicates, also deny update.
        List<String> newExternalIds = new ArrayList<>();

        List<UpdateEventForm> newUpdateForms = new ArrayList<>();
        // Go through each event found and map new values
        for(EventModel em : events){
            Optional<UpdateEventForm> foundUpdateForm =
                    apiReqData.getItems().stream()
                            .filter( it ->
                                    (it.getId() != null && it.getId().equals(UUID.fromString(em.getId())))
                                            || (it.getExternalId() != null && it.getExternalId().equals(em.getExternalId())))
                            .findFirst();
            if(foundUpdateForm.isPresent()){
                // Must be able to write the event's current dataset before mutating it.
                dataSecurity.assertCanWriteDataSet(em.getDataSetId());
                UpdateEventForm updateForm = foundUpdateForm.get();

                UpdateEventForm newUpdateForm = new UpdateEventForm();
                newUpdateForm.setId(UUID.fromString(em.getId()));
                newUpdateForm.setExternalId(updateForm.getExternalId());
                newUpdateForm.setUpdate(updateForm.getUpdate());

                if(updateForm.getUpdate() != null){

                    if(updateForm.getUpdate().getExternalId().getSet() != null){
                        newExternalIds.add(updateForm.getUpdate().getExternalId().getSet());
                    }

                    // Update Event object
                    validateAndUpdate(updateForm, em, resourcesById, resourcesByExternalIdHash);
                    newUpdateForms.add(newUpdateForm);
                }
            }
        }

        validateNewExternalIds(newExternalIds);

        // Final normalization pass, so the models published to Pulsar — the ones the ClickHouse
        // mutation derives its three columns from — carry fully resolved, deduped entries.
        resolveRelatedResources(events, resources);

        // Send to pulsar topic
        var message = new EventCudMessage();
        message.setEvents(events);
        message.setUpdateEvents(newUpdateForms);
        // We are using ReplacingMergeTree, so Clickhouse will just reinsert and automatically
        // merge in background based on event id
        message.setEventAction(EventAction.UPDATE);
        message.setEventObject(EventObject.EVENT);
        // Don't send to Pulsar inline. Publishing a domain event defers the real
        // eventMessageProducer.send(...) until this @Transactional method commits — see
        // AfterCommitMessagePublisher#onEventCud (@TransactionalEventListener AFTER_COMMIT) for
        // the actual send. This closes the dual-write window where a message published before a
        // subsequent rollback would leave the downstream ClickHouse/KVRocks/Neo4j consumers
        // acting on state that was never committed to Postgres.
        applicationEventPublisher.publishEvent(new EventCudPublishEvent(message));

        // Create return result
        DataWrapper<EventModel> result = new DataWrapper<>();
        result.setItems(events);
        return result;
    }

    private void validateNewExternalIds(List<String> newExternalIds) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        errors.setError(new BadRequestError());

        Set<String> existingExternalIdSet = new HashSet<>();

        Set<String> set = new HashSet<>();
        for (String str : newExternalIds) {
            if (!set.add(str)) {
                existingExternalIdSet.add(str);
            }
        }

        List<Map<String, String>> existingExternalIds = new ArrayList<>(existingExternalIdSet.stream()
                .map(it -> Map.of("externalId", it))
                .toList());

        try{
            Map<UUID, BigInteger> results = kvRocksService.findEventIdsByExternalIdCollectionAsMap(set);
            if(!results.isEmpty()){
                for(var entry : results.entrySet()){
                    set.stream()
                            .filter( it -> {
                                var tId = TenantContext.getTenantId();
                                var key = IdGenerator.generate128bitKey(it, tId);
                                return key.equals(entry.getValue());
                            }).findFirst()
                            .ifPresent( it -> {
                                existingExternalIds.add( Map.of("externalId", it) );
                            });
                }
            }
        } catch (Exception e){
            throw new RuntimeException(e.getMessage(), e);
        }

        if(!existingExternalIds.isEmpty()){
            ResponseError<DuplicateError> responseError = new ResponseError<>();
            var duplicateError = new DuplicateError();
            duplicateError.setMessage("DataSet with externalId's already exists.");
            duplicateError.setDuplicated(existingExternalIds);
            responseError.setError(duplicateError);
            throw new DuplicateDataException(responseError);
        }
    }

    public EventModel validateAndUpdate(UpdateEventForm form, EventModel em,
                                        Map<Long, NameAndExternalId> resourcesById,
                                        Map<Long, NameAndExternalId> resourcesByExternalIdHash){
        ResponseError<BadRequestError> errors = new ResponseError<>();
        if(form.getUpdate() != null && !form.getUpdate().validateFields()){
            errors.setError(new BadRequestError());
            form.getUpdate().getErrors().forEach( error -> {
                errors.getError().addFieldError(error.getObjectName(), error.getDefaultMessage());
            });
            throw new BadRequestException(errors);
        }

        EventFields fields = form.getUpdate();

        em.setLastUpdatedTime(ZonedDateTime.now());

        // Update externalId
        if(fields.getExternalId().getSet() != null){
            String newExternalId = fields.getExternalId().getSet();
            em.setExternalId(newExternalId);
        }

        /**
         * Update metadata
         * If key found, update metadata value in existing entry,
         * If key not found, add entry
         * If remove, delete metadata entry
         */
        if(fields.getMetadata().getSet() != null){
            em.setMetadata(fields.getMetadata().getSet());
        }

        // `add` and `remove` default to null on a freshly-constructed UpdateMapField, so a
        // PATCH that only touches other fields leaves these null. Treat null as "no-op".
        var mdAdd = fields.getMetadata().getAdd();
        if(mdAdd != null && !mdAdd.isEmpty()){
            // Start from the existing metadata and layer the added entries on top, so an added key
            // overwrites the old value rather than the other way round.
            Map<String, String> merged = new HashMap<>(em.getMetadata());
            merged.putAll(mdAdd);
            em.setMetadata(merged);
        }

        var mdRemove = fields.getMetadata().getRemove();
        if(mdRemove != null && !mdRemove.isEmpty()){
            em.getMetadata().keySet().removeIf(mdRemove::contains);
        }

        // Update description field
        if(fields.getDescription().getSet() != null){
            em.setDescription(fields.getDescription().getSet());
        }
        // To set description to null, fields.description.setNull must be set to true
        if(fields.getDescription().getSetNull()){
            em.setDescription(null);
        }

        // Update type field. No setNull branch: events.type is non-nullable in ClickHouse and
        // required on create, so EventFields rejects the request with a 400 before we get here.
        if(fields.getType().getSet() != null){
            em.setType(fields.getType().getSet());
        }

        // Update subType field
        if(fields.getSubType().getSet() != null){
            em.setSubType(fields.getSubType().getSet());
        }
        if(fields.getSubType().getSetNull()){
            em.setSubType(null);
        }

        // Update status field
        if(fields.getStatus().getSet() != null){
            em.setStatus(fields.getStatus().getSet());
        }
        if(fields.getStatus().getSetNull()){
            em.setStatus(null);
        }

        // Update source field
        if(fields.getSource().getSet() != null){
            em.setSource(fields.getSource().getSet());
        }
        // To set source to null, fields.source.setNull must be set to true
        if(fields.getSource().getSetNull()){
            em.setSource(null);
        }

        // Update dataset id field
        if(fields.getDataSetId().getSet() != null){
            // Moving an event into a dataset also requires write access to the target.
            dataSecurity.assertCanWriteDataSet(fields.getDataSetId().getSet());
            em.setDataSetId(fields.getDataSetId().getSet());
        }
        // An event's dataset is genuinely optional — the ClickHouse column is non-nullable but 0 is
        // the "no dataset" sentinel BatchedEventsListener maps a null id onto — so unlike type and
        // externalId this one can honestly be cleared. It previously fell through and 200'd
        // unchanged.
        else if(fields.getDataSetId().getSetNull()){
            em.setDataSetId(null);
        }

        // Update related resources field. `set` replaces the list, then `add` and `remove` merge
        // against it — all three keyed on the resolved node id, so an entry can be added or
        // removed by whichever side the caller happens to know.
        var relatedResourceUpdate = fields.getRelatedResources();
        if(relatedResourceUpdate.getSet() != null
                || relatedResourceUpdate.getAdd() != null
                || relatedResourceUpdate.getRemove() != null){

            Collection<IdCollection> base = relatedResourceUpdate.getSet() != null
                    ? relatedResourceUpdate.getSet() : em.getRelatedResources();

            Map<Long, IdCollection> merged = new LinkedHashMap<>();
            for(IdCollection entry : resolveRelatedResources(base, resourcesById, resourcesByExternalIdHash)){
                merged.putIfAbsent(entry.getId(), entry);
            }
            if(relatedResourceUpdate.getAdd() != null){
                for(IdCollection entry : resolveRelatedResources(relatedResourceUpdate.getAdd(), resourcesById, resourcesByExternalIdHash)){
                    merged.putIfAbsent(entry.getId(), entry);
                }
            }
            if(relatedResourceUpdate.getRemove() != null){
                for(IdCollection entry : resolveRelatedResources(relatedResourceUpdate.getRemove(), resourcesById, resourcesByExternalIdHash)){
                    merged.remove(entry.getId());
                }
            }
            em.setRelatedResources(new ArrayList<>(merged.values()));
        }

        // Every time we update an event, we need to update the lastUpdatedTime
        em.setLastUpdatedTime(ZonedDateTime.now());

        return em;
    }
    // Deliberately NOT @Transactional: delete() performs no PostgreSQL writes (KVRocks + ClickHouse
    // reads only), so there is no commit whose rollback the AfterCommitMessagePublisher path would
    // need to guard against. Wrapping it in a transaction would only open a per-tenant Postgres
    // connection (via StatelessRoutingDataSource, acquired eagerly at transaction begin) to host an
    // otherwise-empty transaction. See create()/update() above for the transactional case.
    public void delete(DataWrapper<UUIDAndExternalIdCollection> apiReqData) throws PulsarClientException {
        // This set will hold all unique UUIDs identified for deletion, either explicitly provided or resolved from external IDs.
        Set<UUID> allUuidsForDeletion = new HashSet<>();
        // This set will hold unique external ID strings that were provided in the request
        // and need a lookup in KVRocks to find their corresponding UUIDs.
        Set<String> externalIdsToResolve = new HashSet<>();

        // This list will store the final EventModels that will be sent to Pulsar for deletion.
        List<EventModel> eventsToSendToPulsar = new ArrayList<>();

        // Phase 1: Process items from the API request to populate initial UUIDs and external IDs for lookup.
        for (UUIDAndExternalIdCollection item : apiReqData.getItems()) {
            if (item.getId() != null) {
                // If an event ID (UUID) is explicitly provided, add it to our set of UUIDs for deletion.
                // If it's a new UUID, create an EventModel and add it to the list for Pulsar.
                if (allUuidsForDeletion.add(item.getId())) {
                    EventModel em = new EventModel();
                    em.setId(item.getId().toString());
                    // If an externalId was also provided alongside the ID, include it for context.
                    if (item.getExternalId() != null) {
                        em.setExternalId(item.getExternalId());
                    }
                    eventsToSendToPulsar.add(em);
                }
            } else if (item.getExternalId() != null) {
                // If only an external ID is provided, add it to the set for KVRocks lookup.
                // We'll process these after the lookup to determine their UUIDs (if found) or handle as "not found".
                externalIdsToResolve.add(item.getExternalId());
            }
        }

        // Phase 2: Look up UUIDs for external IDs in KVRocks.
        if (!externalIdsToResolve.isEmpty()) {
            try {
                // Query KVRocks to find mappings from UUIDs to their associated external ID hashes.
                // The method returns Map<UUID, List<BigInteger>>.
                Map<UUID, BigInteger> kvRocksQueryResult = kvRocksService.findEventIdsByExternalIdCollectionAsMap(externalIdsToResolve);

                // Create a reverse map: BigInteger (hash of external ID) -> Set<UUID>.
                // This allows us to easily find all UUIDs associated with a given external ID hash.
                Map<BigInteger, Set<UUID>> externalIdHashToUuidsMap = new HashMap<>();
                for (Map.Entry<UUID, BigInteger> entry : kvRocksQueryResult.entrySet()) {
                    UUID uuid = entry.getKey();
                    BigInteger externalIdHash = entry.getValue();
                    externalIdHashToUuidsMap.computeIfAbsent(externalIdHash, k -> new HashSet<>()).add(uuid);
                }

                // Iterate through the external IDs that needed resolution.
                for (String externalId : externalIdsToResolve) {
                    var tId = TenantContext.getTenantId();
                    BigInteger extId = IdGenerator.generate128bitKey(externalId, tId);
                    Set<UUID> resolvedUuids = externalIdHashToUuidsMap.get(extId);

                    if (resolvedUuids != null && !resolvedUuids.isEmpty()) {
                        // Case: External ID successfully resolved to one or more UUIDs.
                        for (UUID resolvedUuid : resolvedUuids) {
                            // Add this resolved UUID to our master set (to track all UUIDs for deletion)
                            // and if it's new, create an EventModel for it.
                            if (allUuidsForDeletion.add(resolvedUuid)) {
                                EventModel em = new EventModel();
                                em.setId(resolvedUuid.toString());
                                em.setExternalId(externalId); // Retain original externalId for context
                                eventsToSendToPulsar.add(em);
                            }
                        }
                    } else {
                        // Case: "id not found". External ID was provided, but KVRocks did not resolve it to any UUID.
                        // Create an EventModel with only the externalId for deletion.
                        // This ensures that even if no UUID is found, the deletion attempt for this externalId is still propagated.
                        // Prevent adding duplicate EventModels for the same 'not found' externalId.
                        boolean alreadyAddedAsNotFound = eventsToSendToPulsar.stream()
                                .anyMatch(em -> em.getId() == null && externalId.equals(em.getExternalId()));
                        if (!alreadyAddedAsNotFound) {
                            EventModel em = new EventModel();
                            em.setExternalId(externalId);
                            eventsToSendToPulsar.add(em);
                        }
                    }
                }
            } catch (Exception e) {
                // Log and rethrow for any issues during KVRocks lookup
                throw new RuntimeException("Error resolving external IDs from KVRocks: " + e.getMessage(), e);
            }
        }

        // Enforce write permission on the datasets of the events being deleted. We re-read the
        // resolved events from ClickHouse to learn their data_set_id (the request only carries
        // ids/externalIds). Skipped entirely for write-all callers.
        if (!dataSecurity.hasWriteAccessToEverything() && !allUuidsForDeletion.isEmpty()) {
            List<EventModel> existing = clickHouseEventService.findAllById(
                    allUuidsForDeletion, TenantContext.getTenantId(), EventModel.class);
            existing.forEach(e -> dataSecurity.assertCanWriteDataSet(e.getDataSetId()));
        }

        // Phase 3: Send the aggregated events for deletion via Pulsar.
        var message = new EventCudMessage();
        message.setEvents(eventsToSendToPulsar); // Use the fully constructed list of events
        message.setEventAction(EventAction.DELETE);
        message.setEventObject(EventObject.EVENT);

        // Sent synchronously and inline rather than via the transactional AfterCommitMessagePublisher
        // path used by create()/update(): with no PostgreSQL transaction here there is no commit to
        // wait for, and a @TransactionalEventListener would in fact never fire without an active
        // transaction (the event would be silently dropped). A send failure propagates to the caller.
        //
        // Keyed by tenantId (set in the EventCudMessage constructor) so this DELETE lands on the
        // same partition as the tenant's CREATE/UPDATE on the partitioned events topic — matching
        // AfterCommitMessagePublisher. An unkeyed send() would round-robin and could be applied
        // out of order relative to earlier events for the same tenant.
        eventMessageProducer.newMessage()
                .key(message.getTenantId())
                .value(message)
                .send();
    }

    public long count(){
        return clickHouseEventService.count();
    }

    public DataWrapper<EventModel> findById(@NotNull String id) {
        return clickHouseEventService.findById(id, readAclOrNull());
    }

    // ---- event categorical dimensions (type / sub_type / status / source) ----

    public DataWrapper<String> listTypes(String query, int limit) {
        return listDimension(EventDimensionRepository.Dimension.TYPE, query, limit);
    }

    public DataWrapper<String> listSubTypes(String query, int limit) {
        return listDimension(EventDimensionRepository.Dimension.SUB_TYPE, query, limit);
    }

    public DataWrapper<String> listStatuses(String query, int limit) {
        return listDimension(EventDimensionRepository.Dimension.STATUS, query, limit);
    }

    public DataWrapper<String> listSources(String query, int limit) {
        return listDimension(EventDimensionRepository.Dimension.SOURCE, query, limit);
    }

    private DataWrapper<String> listDimension(EventDimensionRepository.Dimension dimension, String query, int limit) {
        DataWrapper<String> data = new DataWrapper<>();
        // Narrowed in SQL to the caller's readable datasets (null = read-all).
        data.setItems(eventDimensionRepository.listDistinct(dimension, readAclOrNull(), query, limit));
        return data;
    }
}
