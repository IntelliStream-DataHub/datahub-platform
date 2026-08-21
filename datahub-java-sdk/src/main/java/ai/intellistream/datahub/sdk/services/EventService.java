// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.sdk.ingest.DurableSpool;
import ai.intellistream.datahub.sdk.ingest.EventIngestor;
import ai.intellistream.datahub.sdk.ingest.IngestOptions;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import ai.intellistream.datahub.sdk.util.UuidV7;
import ai.intellistream.datahub.models.SearchBody;
import tools.jackson.databind.JavaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Events — record and query operational events. Mirrors the {@code /events} endpoints. */
public final class EventService {

    private static final String CREATE_PATH = "/events/create";

    private final ApiHttp http;
    private final EventIngestor ingestor;
    private final DurableSpool<EventModel> spool; // nullable: durable buffering disabled
    private final JavaType eventWrapper; // DataWrapper<EventModel>
    private final JavaType stringWrapper; // DataWrapper<String>
    private final JavaType countType;     // Map<String, Object> — /events/count returns {"count": N}

    public EventService(ApiHttp http) {
        this(http, null);
    }

    public EventService(ApiHttp http, DurableSpool<EventModel> spool) {
        this.http = http;
        this.spool = spool;
        this.ingestor = new EventIngestor(http, CREATE_PATH);
        this.eventWrapper = http.typeFactory().constructParametricType(DataWrapper.class, EventModel.class);
        this.stringWrapper = http.typeFactory().constructParametricType(DataWrapper.class, String.class);
        this.countType = http.typeFactory().constructMapType(java.util.HashMap.class, String.class, Object.class);
    }

    /**
     * POST /events/filter — structured filtering. Criteria AND together; within a list field the
     * entries OR: {@code externalId}, {@code source}, {@code type}, {@code subType},
     * {@code status}, {@code dataSetId}, {@code relatedResources}, {@code metadata}, and the
     * {@code createdTime} / {@code lastUpdatedTime} / {@code eventTime} ranges.
     *
     * <p>The OR'd fields are named in the singular but still take lists — each accepts a bare value
     * or an array, so {@code type: "Alarm"} and {@code type: ["Alarm", "Warning"]} are both valid.
     * {@code relatedResources} stays plural because its entries AND.
     *
     * <p>{@code type}, {@code subType}, {@code status} and {@code source} are pattern lists —
     * {@code *} and {@code %} are wildcards, {@code _} is literal — so "alarms and warnings" is one
     * call rather than two. {@code externalId} takes literals or patterns in the same list, but
     * note that <b>a literal entry matches case-sensitively here</b>, unlike the node filters:
     * events hash the external id verbatim. A wildcard entry goes through ILIKE instead, so
     * {@code "SHIFT_REPORT_1*"} is the case-insensitive way to ask the same question.
     *
     * <p>{@code metadata} and {@code relatedResources} require all entries rather than any, and a
     * null metadata value asks for the key alone. {@code dataSetId} expands down the
     * {@code BELONGS_TO} hierarchy and is the one list where null and empty differ: omitted places
     * no restriction, an explicit {@code []} matches nothing. Note that it takes
     * {@code {"id": ...}} / {@code {"externalId": ...}} objects, not bare ids.
     *
     * <p>This filter deliberately has no {@code id} or {@code name}: events live in ClickHouse
     * rather than the node table, their id is a UUID string, and the table has only a
     * {@code description}. Fetch by id with {@link #byIds(List)} or {@link #getById(String)}.
     *
     * <p>Capped by the retriever's {@code limit} (default 1000, max 10000). Past that cap, page
     * with the retriever's {@code cursor} and the response's {@code nextCursor}.
     */
    public DataWrapper<EventModel> filter(EventRetreiver retriever) {
        return http.post("/events/filter", retriever, eventWrapper);
    }

    /** {@link #filter(EventRetreiver)} with just the criteria and the default limit. */
    public DataWrapper<EventModel> filter(EventFilter criteria) {
        EventRetreiver request = new EventRetreiver();
        request.setFilter(criteria);
        return filter(request);
    }

    /** POST /events/byids */
    public DataWrapper<EventModel> byIds(List<IdCollection> ids) {
        return http.post("/events/byids", new DataWrapper<IdCollection>().setItems(ids), eventWrapper);
    }

    /** POST /events/create */
    public DataWrapper<EventModel> create(List<EventModel> events) {
        return http.post(CREATE_PATH, new DataWrapper<EventModel>().setItems(events), eventWrapper);
    }

    /**
     * GET /events/{id} — one event by its UUID.
     *
     * <p>Unlike {@link #byIds(List)}, which omits what it cannot find, this is a 404 when the event
     * does not exist.
     */
    public DataWrapper<EventModel> getById(String id) {
        return http.get("/events/" + id, eventWrapper);
    }

    /**
     * POST /events/search — free-text search over event descriptions.
     *
     * <p>A case-insensitive <em>substring</em> match over {@code externalId}, {@code description}
     * and the metadata values, newest first by {@code eventTime}. Unlike the resource, data set and
     * timeseries searches it is not word-aware and does not rank by relevance — events live in
     * ClickHouse and have no full-text index. {@code SearchBody<EventFilter>.filter} takes the same
     * {@link ai.intellistream.datahub.models.events.EventFilter} as {@link #filter(EventRetreiver)}
     * and only ever removes matches.
     *
     * <p>{@code limit} is capped at 1000 here, against 10000 on {@link #filter(EventRetreiver)}.
     * Prefer {@code filter} for structured questions — it is faster and its results are predictable.
     */
    public DataWrapper<EventModel> search(SearchBody<EventFilter> form) {
        return http.post("/events/search", form, eventWrapper);
    }

    /**
     * POST /events/update — change fields on events that already exist.
     *
     * <p>Only the fields named in each entry's {@code update} block change. Prefer appending a
     * corrective event to mutating one where the record matters for audit: an update is a
     * replace-and-cleanup, so a concurrent read can briefly return the pre-update version or see
     * the event twice.
     */
    public DataWrapper<EventModel> update(List<UpdateEventForm> updates) {
        return http.post("/events/update", new DataWrapper<UpdateEventForm>().setItems(updates), eventWrapper);
    }

    /**
     * GET /events/count — how many events the tenant holds.
     *
     * <p>Takes no filter. For a filtered count, run {@link #filter(EventRetreiver)} and measure the
     * page.
     */
    public long count() {
        Map<String, Object> response = http.get("/events/count", countType);
        Object count = response.get("count");
        return count instanceof Number n ? n.longValue() : 0L;
    }

    /** GET /events/list/types — every distinct {@code type} on events you can read, alphabetically. */
    public DataWrapper<String> listTypes(Integer limit) {
        return distinct("/events/list/types", null, limit);
    }

    /** GET /events/list/sub-types — every distinct {@code subType}, alphabetically. */
    public DataWrapper<String> listSubTypes(Integer limit) {
        return distinct("/events/list/sub-types", null, limit);
    }

    /** GET /events/list/statuses — every distinct {@code status}, alphabetically. */
    public DataWrapper<String> listStatuses(Integer limit) {
        return distinct("/events/list/statuses", null, limit);
    }

    /** GET /events/list/sources — every distinct {@code source}, alphabetically. */
    public DataWrapper<String> listSources(Integer limit) {
        return distinct("/events/list/sources", null, limit);
    }

    /** GET /events/search/type — distinct {@code type} values containing {@code q}, for type-ahead. */
    public DataWrapper<String> searchTypes(String q, Integer limit) {
        return distinct("/events/search/type", q, limit);
    }

    /** GET /events/search/sub-type — distinct {@code subType} values containing {@code q}. */
    public DataWrapper<String> searchSubTypes(String q, Integer limit) {
        return distinct("/events/search/sub-type", q, limit);
    }

    /** GET /events/search/status — distinct {@code status} values containing {@code q}. */
    public DataWrapper<String> searchStatuses(String q, Integer limit) {
        return distinct("/events/search/status", q, limit);
    }

    /** GET /events/search/source — distinct {@code source} values containing {@code q}. */
    public DataWrapper<String> searchSources(String q, Integer limit) {
        return distinct("/events/search/source", q, limit);
    }

    /**
     * Shared shape of the eight distinct-value endpoints: a GET returning {@code {"items": [...]}},
     * optionally narrowed by the case-insensitive substring {@code q}. {@code limit} is clamped
     * server-side to 1..10000; pass {@code null} for the server default of 1000.
     */
    private DataWrapper<String> distinct(String path, String q, Integer limit) {
        StringBuilder url = new StringBuilder(path);
        char separator = '?';
        if (q != null) {
            url.append(separator).append("q=").append(URLEncoder.encode(q, StandardCharsets.UTF_8));
            separator = '&';
        }
        if (limit != null) {
            url.append(separator).append("limit=").append(limit);
        }
        return http.get(url.toString(), stringWrapper);
    }

    /** POST /events/delete */
    public DataWrapper<EventModel> delete(List<IdCollection> ids) {
        return http.post("/events/delete", new DataWrapper<IdCollection>().setItems(ids), eventWrapper);
    }

    /** Ingest events concurrently with the default {@link IngestOptions}. */
    public IngestResult ingest(List<EventModel> events) {
        return ingest(events, IngestOptions.defaults());
    }

    /**
     * Ingest events concurrently — chunked, parallelised and retried per {@code options}. When the
     * client has durable buffering enabled, previously spooled events are flushed first and, if the
     * send fails, the batch is spooled to disk for a later retry; see {@link IngestResult#buffered()}.
     */
    public IngestResult ingest(List<EventModel> events, IngestOptions options) {
        requireEventTimes(events); // event_time is the caller's to set; the SDK won't guess it
        stampMissingIds(events); // stable id before first send, so a retry dedups instead of duplicating
        if (spool == null) {
            return ingestor.ingest(events, options);
        }
        long now = System.currentTimeMillis();

        boolean drainedAll = spool.size() == 0
                || spool.drain(chunk -> sendChunk(chunk, options), now);
        if (!drainedAll) {
            spool.append(events, now); // backlog stuck (server down): just buffer the new events
            return IngestResult.buffered(spool.size());
        }

        IngestResult live = ingestor.ingest(events, options);
        if (live.isComplete()) {
            return live.withBuffered(0);
        }
        if (live.isBufferable()) {
            spool.append(events, now);
            return IngestResult.buffered(spool.size());
        }
        return live.withBuffered(0); // terminal failure: surface it rather than buffer forever
    }

    /** Drain callback: send one spooled chunk. Keep bufferable failures (transient or auth) spooled; drop terminal ones. */
    private boolean sendChunk(List<EventModel> chunk, IngestOptions options) {
        IngestResult result = ingestor.ingest(chunk, options);
        if (result.isComplete()) {
            return true;
        }
        if (result.isBufferable()) {
            return false; // stop draining; server unreachable or auth not yet restored
        }
        System.err.println("DataHub SDK: dropping spooled events after a terminal error: " + result.errors());
        return true; // terminal: drop so the spool can't get stuck forever
    }

    /**
     * Require an explicit {@code eventTime} on every event. It is the time the event occurred (from the
     * source system or sensor); the SDK won't default it to "now" because that is usually wrong, and the
     * server records ingestion time separately as {@code createdTime}.
     */
    private static void requireEventTimes(List<EventModel> events) {
        for (EventModel event : events) {
            if (!event.hasEventTime()) {
                throw new IllegalArgumentException(
                        "event '" + event.getExternalId() + "' is missing eventTime. Set the time the "
                        + "event occurred (from your source system or sensor) via setEventTime(...); the "
                        + "SDK won't default it to the current time because that is usually incorrect. "
                        + "The server records the ingestion time separately as createdTime.");
            }
        }
    }

    /**
     * Give each event that lacks one a stable UUID v7 id, so the spooled copy and any retry carry the
     * same id and the backend collapses duplicates (rather than the server minting a fresh id per send).
     * Requires the server to honor a client-supplied id; see the SDK README.
     */
    private static void stampMissingIds(List<EventModel> events) {
        for (EventModel event : events) {
            if (event.getId() == null || event.getId().isBlank()) {
                event.setId(UuidV7.generateString());
            }
        }
    }
}
