// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.api.mcp.dto.McpList;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import ai.intellistream.datahub.models.events.LeanEvent;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.SearchForm;
import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventQueryResult;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.models.validation.EventFields;
import ai.intellistream.datahub.models.SearchBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class EventMcpTools {

    private final EventService eventService;

    public EventMcpTools(EventService eventService) {
        this.eventService = eventService;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "event_create",
            description = """
                    Create a single event. Events record something that happened — an
                    alarm, a work-order creation, a state transition. externalId is a
                    stable snake_case identifier; type/subType are free-form strings
                    (e.g. type='Alarm', subType='Electrical').

                    Pass relatedResourceExternalIds whenever the event is about specific
                    resources — that link is what later lets anyone find this event by the
                    equipment it concerns.
                    """
    )
    public DataWrapper<EventModel> createEvent(
            @ToolParam(description = "Stable snake_case id. 3–256 chars.")
            String externalId,
            @ToolParam(description = "Event type (3–128 chars, e.g. 'Alarm').")
            String type,
            @ToolParam(description = "Id of the owning dataset.")
            Long dataSetId,
            @ToolParam(description = "Event time as ISO-8601 (e.g. '2026-04-23T13:00:00Z').")
            String eventTime,
            @ToolParam(required = false, description = "Optional subtype (3–128 chars).")
            String subType,
            @ToolParam(required = false, description = "Optional status, e.g. 'COMPLETE' / 'FAILED'.")
            String status,
            @ToolParam(required = false, description = "Optional description.")
            String description,
            @ToolParam(required = false, description =
                    "ExternalIds of the resources this event is about. They must already exist.")
            List<String> relatedResourceExternalIds
    ) throws Exception {
        EventModel e = new EventModel();
        e.setExternalId(externalId);
        e.setType(type);
        e.setDataSetId(dataSetId);
        e.setEventTime(java.time.ZonedDateTime.parse(eventTime));
        if (subType != null) e.setSubType(subType);
        if (status != null) e.setStatus(status);
        if (description != null) e.setDescription(description);
        if (relatedResourceExternalIds != null) {
            relatedResourceExternalIds.stream()
                    .filter(Objects::nonNull)
                    .map(IdCollection::createFromExternalId)
                    .forEach(e.getRelatedResources()::add);
        }

        DataWrapper<EventModel> req = new DataWrapper<>();
        req.getItems().add(e);
        return eventService.create(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "event_get",
            description = """
                    Fetch the FULL detail of specific events by externalId and/or UUID, in one
                    call. Pass one or many of each (e.g. externalIds=["a","b"]); every match is
                    returned, missing ones are simply absent. Use this when you already know the
                    identifiers and want complete records; for browsing or compact results use
                    event_filter instead.
                    """
    )
    public DataWrapper<EventModel> getEvents(
            @ToolParam(required = false, description = "ExternalIds to look up.")
            List<String> externalIds,
            @ToolParam(required = false, description = "Event UUIDs to look up.")
            List<String> ids
    ) {
        List<UUIDAndExternalIdCollection> refs = buildRefs(externalIds, ids);
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("Supply at least one externalId or id");
        }
        return eventService.findAllByIdAndExternalId(refs);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "event_search",
            description = """
                    Substring search for events whose externalId, description or metadata
                    values contain the query, newest first. Returns compact events (like
                    event_filter's drill-down); for the full record of a known event use
                    event_get. Capped by 'limit' (default 100, max 1000).
                    """
    )
    public McpList<LeanEvent> searchEvents(
            @ToolParam(description = "Search text.")
            String query,
            @ToolParam(required = false, description = "Max results (default 100, max 1000).")
            Integer limit
    ) {
        int cap = (limit == null) ? 100 : limit;
        SearchBody<EventFilter> s = new SearchBody<>();
        SearchForm sf = new SearchForm();
        sf.setQuery(query);
        s.setSearch(sf);
        s.setLimit(cap);
        List<LeanEvent> items = eventService.search(s).getItems().stream()
                .map(EventService::toLean).toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "event_filter",
            description = """
                    Structured event query — use this (not event_search) to filter events by
                    time range or exact field values. It works in two modes:

                    1. COUNT/AGGREGATE: set 'groupBy' to one of type, subType, source,
                       dataSetId, externalId, day, hour. Returns buckets of {value, count}
                       (most frequent first) plus a total — NOT the events. Start here to
                       answer "how many, and of what kinds?" cheaply, e.g. groupBy='type'
                       over a time window. groupBy='externalId' is special: events that share
                       an externalId are the lifecycle of one logical event (e.g. a purchase
                       order moving created -> requested -> approved -> paid), so the count is
                       how many state transitions that event has; the busiest lifecycles
                       surface first.
                    2. DRILL-DOWN: omit 'groupBy'. Returns up to 'limit' compact events
                       (default 25, max 10000) — only the key fields (externalId, type,
                       subType, eventTime, description, and dataSetId/metadata/related when
                       present). Use a bucket value from mode 1 to narrow first.

                    'start'/'end' filter on eventTime — WHEN THE EVENT OCCURRED (business
                    time), inclusive/exclusive — which is how you scope "the last 12 hours".
                    'type'/'subType'/'source' are exact, case-sensitive matches; values are
                    tenant-defined, so prefer discovering them via groupBy rather than
                    guessing. To filter by status, use the REST /events/filter endpoint.

                    'dataSetId' / 'dataSetExternalId' restrict to a LIST of datasets, by id
                    or by externalId — use whichever you have, or both. They match exactly: a
                    parent dataset does not stand in for its children, so name every dataset you
                    want. Pair them with groupBy='dataSetId' — aggregate first to see which
                    datasets are in play, then drill down on the ones that matter.

                    'relatedResources' / 'relatedResourceExternalIds' narrow to events attached
                    to specific resources — the way to answer "what happened to this pump?".
                    Supplying several requires the event to be attached to ALL of them.
                    """
    )
    public EventQueryResult filterEvents(
            @ToolParam(required = false, description = "Exact event type (case-sensitive).")
            String type,
            @ToolParam(required = false, description = "Exact event subType (case-sensitive).")
            String subType,
            @ToolParam(required = false, description = "Exact event source (case-sensitive).")
            String source,
            @ToolParam(required = false, description =
                    "Match events whose externalId matches this (snake_case). `*` is a wildcard, so "
                    + "'work_order_*' is a prefix search and '*_closed' a suffix one; without a "
                    + "wildcard it matches that one externalId exactly. Underscores are literal.")
            String externalId,
            @ToolParam(required = false, description =
                    "Restrict to events in these datasets, by dataset id. Matched exactly, so "
                    + "include child datasets explicitly if you want them. Get ids from "
                    + "dataset_list / dataset_search, or from groupBy='dataSetId'. Omit for "
                    + "every dataset you can read.")
            List<Long> dataSetId,
            @ToolParam(required = false, description =
                    "The same restriction by dataset externalId (snake_case) rather than id — "
                    + "usually the easier one to reach for, since dataset_search returns names. "
                    + "Combines with dataSetId as a union. An externalId matching no dataset "
                    + "contributes nothing, so a list of only unknown names returns no events.")
            List<String> dataSetExternalId,
            @ToolParam(required = false, description =
                    "Restrict to events attached to ALL of these resources, by resource id.")
            List<Long> relatedResources,
            @ToolParam(required = false, description =
                    "The same restriction by resource externalId. Combines with relatedResources: "
                    + "the event must be attached to every resource named either way.")
            List<String> relatedResourceExternalIds,
            @ToolParam(required = false, description =
                    "Start of the eventTime window (inclusive), ISO-8601 e.g. '2026-06-24T00:00:00Z'.")
            String start,
            @ToolParam(required = false, description = "End of the eventTime window (exclusive), ISO-8601.")
            String end,
            @ToolParam(required = false, description =
                    "Aggregate into counts grouped by this field instead of returning events. "
                    + "One of: type, subType, source, dataSetId, externalId, day, hour. "
                    + "externalId groups the lifecycle states of one logical event together.")
            String groupBy,
            @ToolParam(required = false, description = "Max events in drill-down mode (default 25, max 10000).")
            Integer limit
    ) {
        EventFilter filter = new EventFilter();
        if (type != null) filter.setType(List.of(type));
        if (subType != null) filter.setSubType(List.of(subType));
        if (source != null) filter.setSource(List.of(source));
        if (externalId != null) filter.setExternalId(List.of(externalId));
        // Ids and externalIds arrive as separate arguments because a tool schema reads better with
        // flat arrays than with one array of {id|externalId} objects, and because an all-digit
        // externalId is legal — flattening the two would make the tool guess which one it was given.
        // Each pair is named for the EventFilter field it fills, so the vocabulary a model queries
        // with is the vocabulary the REST contract uses.
        //
        // An empty list from a model means "I have no dataset in mind", so it is left unset. The REST
        // surface reads an explicit [] the other way — as "narrow to no datasets" — because a client
        // hand-writing it means exactly that; a model emitting it does not.
        List<IdCollection> dataSets = references(dataSetId, dataSetExternalId);
        if (!dataSets.isEmpty()) filter.setDataSetId(dataSets);
        List<IdCollection> resources = references(relatedResources, relatedResourceExternalIds);
        if (!resources.isEmpty()) filter.setRelatedResources(resources);

        if (start != null || end != null) {
            TimeFilter eventTime = new TimeFilter();
            if (start != null) eventTime.setMin(ZonedDateTime.parse(start));
            if (end != null) eventTime.setMax(ZonedDateTime.parse(end));
            filter.setEventTime(eventTime);
        }

        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        // Drill-down defaults to a small page so the payload stays LLM-friendly; aggregate
        // mode ignores the limit. Note: EventRetreiver.setLimit coerces <=0 to 100.
        retreiver.setLimit(limit != null ? limit : 25);
        return eventService.queryEvents(retreiver, groupBy);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "event_update",
            description = """
                    Update common fields on a single event. Identify by id (UUID) or
                    externalId. Only provided fields are set. For niche patch operations
                    (metadata add/remove, related-resource list updates), use the REST
                    API's PATCH /events with a `relatedResources` set/add/remove block.
                    """
    )
    public DataWrapper<EventModel> updateEvent(
            @ToolParam(required = false, description = "UUID of the event.")
            String id,
            @ToolParam(required = false, description = "ExternalId of the event.")
            String externalId,
            @ToolParam(required = false, description = "New externalId (snake_case).")
            String newExternalId,
            @ToolParam(required = false, description = "New description.")
            String newDescription,
            @ToolParam(required = false, description = "New type.")
            String newType,
            @ToolParam(required = false, description = "New subType.")
            String newSubType,
            @ToolParam(required = false, description = "New status.")
            String newStatus
    ) throws Exception {
        if ((id == null) == (externalId == null)) {
            throw new IllegalArgumentException("Supply exactly one of id or externalId");
        }
        UpdateEventForm form = new UpdateEventForm();
        if (id != null) form.setId(UUID.fromString(id));
        if (externalId != null) form.setExternalId(externalId);

        EventFields f = new EventFields();
        if (newExternalId != null) f.setExternalId(new UpdateStringField().set(newExternalId));
        if (newDescription != null) f.setDescription(new UpdateStringField().set(newDescription));
        if (newType != null) f.setType(new UpdateStringField().set(newType));
        if (newSubType != null) f.setSubType(new UpdateStringField().set(newSubType));
        if (newStatus != null) f.setStatus(new UpdateStringField().set(newStatus));
        form.setUpdate(f);

        DataWrapper<UpdateEventForm> req = new DataWrapper<>();
        req.getItems().add(form);
        return eventService.update(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "event_delete",
            description = """
                    Delete events by externalId and/or UUID, in one call. Pass one or many of
                    each (e.g. externalIds=["a","b","c"]); all are deleted together. Returns how
                    many references were submitted for deletion.
                    """
    )
    public String deleteEvents(
            @ToolParam(required = false, description = "ExternalIds of events to delete.")
            List<String> externalIds,
            @ToolParam(required = false, description = "UUIDs of events to delete.")
            List<String> ids
    ) throws Exception {
        List<UUIDAndExternalIdCollection> refs = buildRefs(externalIds, ids);
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("Supply at least one externalId or id");
        }
        DataWrapper<UUIDAndExternalIdCollection> req = new DataWrapper<>();
        req.getItems().addAll(refs);
        eventService.delete(req);
        return "deleted " + refs.size() + " event(s)";
    }

    /**
     * Build event references from parallel lists of externalIds and UUIDs (either may be null),
     * skipping null/blank entries. Each non-blank id becomes its own reference, so a single call
     * can mix both kinds.
     */
    /**
     * The {@code <field>} / {@code <field>ExternalId(s)} argument pair as the one list of
     * {@link IdCollection} the filter field actually holds. Nulls and blanks are dropped, so a model
     * padding an array with an empty string narrows to what it did name rather than to nothing.
     */
    private static List<IdCollection> references(List<Long> ids, List<String> externalIds) {
        List<IdCollection> refs = new ArrayList<>();
        if (ids != null) {
            ids.stream().filter(Objects::nonNull).map(IdCollection::createFromId).forEach(refs::add);
        }
        if (externalIds != null) {
            externalIds.stream()
                    .filter(externalId -> externalId != null && !externalId.isBlank())
                    .map(IdCollection::createFromExternalId)
                    .forEach(refs::add);
        }
        return refs;
    }

    private static List<UUIDAndExternalIdCollection> buildRefs(List<String> externalIds, List<String> ids) {
        List<UUIDAndExternalIdCollection> refs = new ArrayList<>();
        if (externalIds != null) {
            for (String externalId : externalIds) {
                if (externalId == null || externalId.isBlank()) continue;
                UUIDAndExternalIdCollection ref = new UUIDAndExternalIdCollection();
                ref.setExternalId(externalId);
                refs.add(ref);
            }
        }
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isBlank()) continue;
                UUIDAndExternalIdCollection ref = new UUIDAndExternalIdCollection();
                ref.setId(UUID.fromString(id));
                refs.add(ref);
            }
        }
        return refs;
    }
}
