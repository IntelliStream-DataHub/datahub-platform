// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.jpa.dto.DatapointAggsDTO;
import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.TimeseriesRetreiver;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.sdk.ingest.DatapointIngestor;
import ai.intellistream.datahub.sdk.ingest.DatapointSpool;
import ai.intellistream.datahub.sdk.ingest.IngestOptions;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import ai.intellistream.datahub.sdk.timeseries.Datapoint;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.models.SearchForm;
import ai.intellistream.datahub.models.SearchBody;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Time-series metadata and datapoints. CRUD over {@code /timeseries}, datapoint retrieval, and
 * both single-request and high-throughput concurrent datapoint ingestion.
 */
public final class TimeseriesService {

    private static final String DATA_PATH = "/timeseries/data";

    private final ApiHttp http;
    private final DatapointIngestor ingestor;
    private final DatapointSpool spool;      // nullable: durable buffering disabled
    private final JavaType timeseries;       // DataWrapper<Timeseries>
    private final JavaType strings;          // DataWrapper<String>
    private final JavaType datapoints;       // DataWrapper<DatapointsCollection>
    private final JavaType aggregatedData;   // DataWrapper<DataCollection<DatapointAggsDTO>>

    public TimeseriesService(ApiHttp http) {
        this(http, null);
    }

    public TimeseriesService(ApiHttp http, DatapointSpool spool) {
        this.http = http;
        this.spool = spool;
        this.ingestor = new DatapointIngestor(http, DATA_PATH);
        TypeFactory tf = http.typeFactory();
        this.timeseries = tf.constructParametricType(DataWrapper.class, Timeseries.class);
        this.strings = tf.constructParametricType(DataWrapper.class, String.class);
        this.datapoints = tf.constructParametricType(DataWrapper.class, DatapointsCollection.class);
        this.aggregatedData = tf.constructParametricType(DataWrapper.class,
                tf.constructParametricType(DataCollection.class, DatapointAggsDTO.class));
    }

    // --- Time-series metadata --------------------------------------------------------------------

    /**
     * GET /timeseries/{id} — fetch one timeseries by its numeric id.
     *
     * <p>A timeseries you may not read comes back as 404 rather than 403, so a hidden series is
     * indistinguishable from a missing one. To look one up by {@code externalId}, or several at
     * once, use {@link #byIds(List)}.
     */
    public DataWrapper<Timeseries> getById(long id) {
        return http.get("/timeseries/" + id, timeseries);
    }

    /** POST /timeseries/create */
    public DataWrapper<Timeseries> create(List<Timeseries> series) {
        return http.post("/timeseries/create", new DataWrapper<Timeseries>().setItems(series), timeseries);
    }

    /**
     * Create one or more series, e.g. a {@code Timeseries} with its externalId, name and unitExternalId set.
     */
    public DataWrapper<Timeseries> create(Timeseries... series) {
        return create(List.of(series));
    }

    /**
     * POST /timeseries/delete — delete timeseries (and their datapoints) by id or external id.
     * Any referencing subscriptions or edges must be removed first, or the backend responds 409.
     */
    public DataWrapper<Timeseries> delete(List<IdCollection> ids) {
        return http.post("/timeseries/delete", new DataWrapper<IdCollection>().setItems(ids), timeseries);
    }

    /** POST /timeseries/byids */
    public DataWrapper<Timeseries> byIds(List<IdCollection> ids) {
        return http.post("/timeseries/byids", new DataWrapper<IdCollection>().setItems(ids), timeseries);
    }

    /**
     * POST /timeseries/filter — structured filtering. Criteria AND together; within a list field
     * the entries OR. Beyond the shared node criteria ({@code id}, {@code externalId},
     * {@code name}, {@code source}, {@code labels}, {@code metadata}, {@code createdTime},
     * {@code lastUpdatedTime}) this filter adds {@code unit}, {@code unitExternalId} and
     * {@code valueType}, and inherits {@code dataSetId}.
     *
     * <p>The OR'd fields are named in the singular but still take lists — each accepts a bare value
     * or an array, so {@code unit: "kg/hr"} and {@code unit: ["kg/hr", "bar"]} are both valid.
     * {@code labels} stays plural because its entries AND.
     *
     * <p>{@code dataSetId} takes ids or externalIds and expands down the {@code BELONGS_TO}
     * hierarchy, so naming a master data set matches every series beneath it. It is the one list
     * where null and empty differ: omitting it places no restriction, while an explicit {@code []}
     * narrows to nothing. Data sets the caller cannot read are silently omitted either way.
     *
     * <p>{@code unit} and {@code unitExternalId} are pattern lists on the same rules as
     * {@code name} — {@code *} and {@code %} are wildcards, {@code _} is literal, case-insensitive
     * — so {@code ["kg/hr", "deg_*"]} is one call rather than several. {@code valueType} is matched
     * exactly against the closed catalogue ({@code BIGINT}, {@code FLOAT}, {@code FLOAT32},
     * {@code NUMERIC}, {@code DECIMAL32}, {@code TEXT}, {@code MIXED}), not as patterns.
     * {@code metadata} entries must all be present, and a null value asks for the key alone —
     * which is what the removed {@code metadataKey}/{@code metadataValue} pair used to say.
     *
     * <p>Results come newest created first unless the retriever carries a {@code sort}, capped by
     * its {@code limit} (default 1000, max 10000). Past that cap, page with the retriever's
     * {@code cursor} and the response's {@code nextCursor}.
     */
    public DataWrapper<Timeseries> filter(TimeseriesRetreiver request) {
        return http.post("/timeseries/filter", request, timeseries);
    }

    /** {@link #filter(TimeseriesRetreiver)} with just the criteria and the default limit. */
    public DataWrapper<Timeseries> filter(TimeseriesFilter criteria) {
        TimeseriesRetreiver request = new TimeseriesRetreiver();
        request.setFilter(criteria);
        return filter(request);
    }

    /**
     * POST /timeseries/search — free-text search over timeseries.
     *
     * <p>The phrase is matched fuzzily and word-aware against {@code name}, {@code externalId} and
     * {@code description}, and results come back ranked by relevance ({@code ts_rank}) with
     * {@code id} as a tie-break, so repeated identical calls agree.
     * {@code SearchBody<TimeseriesFilter>.filter} takes the same
     * {@link TimeseriesFilter} as {@link #filter(TimeseriesFilter)} and only ever removes matches —
     * the phrase decides what the candidates are. {@code limit} defaults to 100 and caps at 1000,
     * against 10000 on {@code filter}; prefer {@code filter} for structured questions, which is
     * faster and whose results are predictable.
     *
     * <p>This was the one search endpoint the SDK did not expose, while resources, data sets and
     * events all had theirs.
     */
    public DataWrapper<Timeseries> search(SearchBody<TimeseriesFilter> request) {
        return http.post("/timeseries/search", request, timeseries);
    }

    /** {@link #search(SearchBody<TimeseriesFilter>)} with just a phrase, no narrowing, and the default limit. */
    public DataWrapper<Timeseries> search(String query) {
        SearchBody<TimeseriesFilter> request = new SearchBody<>();
        SearchForm phrase = new SearchForm();
        phrase.setQuery(query);
        request.setSearch(phrase);
        return search(request);
    }

    // --- Datapoints ------------------------------------------------------------------------------

    /** POST /timeseries/data/list — retrieve datapoints for a time range. */
    public DataWrapper<DatapointsCollection> retrieve(DataRetriever<RetrieveFilter> request) {
        return http.post("/timeseries/data/list", request, datapoints);
    }

    /**
     * POST /timeseries/data/list for AGGREGATED reads: when the filters carry {@code aggregates}
     * (e.g. {@code ["avg"]}) plus a {@code granularity}, each point is a bucketed
     * {@link DatapointAggsDTO} (min/max/avg/sum), so the response deserializes into
     * {@code DataCollection<DatapointAggsDTO>} rather than the raw {@link DatapointsCollection} that
     * {@link #retrieve} yields.
     */
    public DataWrapper<DataCollection<DatapointAggsDTO>> retrieveAggregated(DataRetriever<RetrieveFilter> request) {
        return http.post("/timeseries/data/list", request, aggregatedData);
    }

    /**
     * POST /timeseries/data/delete — clear a window of datapoints from each named series, leaving
     * the series themselves alone. To remove those as well, use {@link #delete(List)}.
     *
     * <p>Each {@link DeleteDatapoint} names one series by {@code id} or {@code externalId} and
     * carries its own half-open window. Both bounds are optional, which gives four calls:
     *
     * <table border="1">
     *   <caption>What each combination of bounds deletes</caption>
     *   <tr><th>Bounds set</th><th>Deleted</th></tr>
     *   <tr><td>begin and end</td><td>The half-open window between them</td></tr>
     *   <tr><td>begin only</td><td>Everything from that instant onward</td></tr>
     *   <tr><td>end only</td><td>Everything before that instant</td></tr>
     *   <tr><td>neither</td><td>Every datapoint of the series, keeping its definition, edges and
     *       subscriptions</td></tr>
     * </table>
     *
     * <p>A bound is ISO-8601 or epoch milliseconds; anything else is a 400 naming the field, as is
     * a series that does not exist. The purge is asynchronous, so this returns once the api has
     * accepted the request rather than once the rows are gone, and a read straight afterwards can
     * still see them. None of it can be undone.
     */
    public void deleteDatapoints(List<DeleteDatapoint> windows) {
        http.send("POST", "/timeseries/data/delete",
                new DataRetriever<DeleteDatapoint>().setItems(windows));
    }

    /** {@link #deleteDatapoints(List)} with the windows given inline. */
    public void deleteDatapoints(DeleteDatapoint... windows) {
        deleteDatapoints(List.of(windows));
    }

    /**
     * {@link #deleteDatapoints(List)} for one series named by external id, with the window as
     * instants rather than strings. A null bound leaves that side open, so two nulls clear the
     * whole series.
     */
    public void deleteDatapoints(String externalId, Instant inclusiveBegin, Instant exclusiveEnd) {
        DeleteDatapoint window = new DeleteDatapoint();
        window.setExternalId(externalId);
        // Instant.toString() is ISO-8601 with a Z offset, one of the two forms the api parses.
        window.setInclusiveBegin(inclusiveBegin == null ? null : inclusiveBegin.toString());
        window.setExclusiveEnd(exclusiveEnd == null ? null : exclusiveEnd.toString());
        deleteDatapoints(List.of(window));
    }

    /**
     * Insert datapoints in a single request (no chunking). Fine for small volumes; for large or
     * unbounded amounts prefer {@link #ingest(List)} / {@link #ingest(List, IngestOptions)}.
     */
    public DataWrapper<String> insertDatapoints(List<DatapointsCollection> data) {
        return http.post(DATA_PATH, new DataWrapper<DatapointsCollection>().setItems(data), strings);
    }

    /** Ingest datapoints concurrently with the default {@link IngestOptions}. */
    public IngestResult ingest(List<DatapointsCollection> data) {
        return ingest(data, IngestOptions.defaults());
    }

    /**
     * Ingest datapoints concurrently — chunked, parallelised and retried per {@code options}. When the
     * client has durable buffering enabled, any previously spooled datapoints are flushed first and,
     * if the send fails (e.g. the server is unreachable), the batch is spooled to disk for a later
     * retry; see {@link IngestResult#buffered()}.
     */
    public IngestResult ingest(List<DatapointsCollection> data, IngestOptions options) {
        if (spool == null) {
            return ingestor.ingest(data, options);
        }
        return ingestDurable(data, options);
    }

    /**
     * Flush any on-disk backlog first (streamed in chunks), then send {@code data}. If the backlog is
     * still stuck or the new send hits a bufferable failure — transient (e.g. server unreachable) or an
     * auth failure (HTTP 401/403, e.g. an expired token) — the new data is spooled for a later retry; a
     * terminal failure (e.g. HTTP 400) is surfaced, not buffered. A mid-segment failure may re-send an
     * already-accepted chunk on the next flush, which is harmless: datapoints are keyed by (series
     * external id, timestamp), so the backend collapses the duplicate.
     */
    private IngestResult ingestDurable(List<DatapointsCollection> data, IngestOptions options) {
        long now = System.currentTimeMillis();

        boolean drainedAll = spool.size() == 0
                || spool.drain(chunk -> sendChunk(chunk, options), now);
        if (!drainedAll) {
            spool.append(data, now); // backlog stuck (server down): just buffer the new data
            return IngestResult.buffered(spool.size());
        }

        IngestResult live = ingestor.ingest(data, options);
        if (live.isComplete()) {
            return live.withBuffered(0);
        }
        if (live.isBufferable()) {
            spool.append(data, now);
            return IngestResult.buffered(spool.size());
        }
        return live.withBuffered(0); // terminal failure: surface it rather than buffer forever
    }

    /** Drain callback: send one spooled chunk. Keep bufferable failures (transient or auth) spooled; drop terminal ones. */
    private boolean sendChunk(List<DatapointsCollection> chunk, IngestOptions options) {
        IngestResult result = ingestor.ingest(chunk, options);
        if (result.isComplete()) {
            return true;
        }
        if (result.isBufferable()) {
            return false; // stop draining; server unreachable or auth not yet restored
        }
        System.err.println("DataHub SDK: dropping spooled datapoints after a terminal error: " + result.errors());
        return true; // terminal: drop so the spool can't get stuck forever
    }

    /**
     * Ingest datapoints grouped by series external id, with the default {@link IngestOptions}.
     * The ergonomic counterpart to {@link #ingest(List)} — e.g.
     * {@code ingest(Map.of("rpm", List.of(Datapoint.of(Instant.now(), 1500.0))))}.
     */
    public IngestResult ingest(Map<String, List<Datapoint>> datapointsByExternalId) {
        return ingest(datapointsByExternalId, IngestOptions.defaults());
    }

    /** Ingest datapoints grouped by series external id — chunked, parallelised and retried. */
    public IngestResult ingest(Map<String, List<Datapoint>> datapointsByExternalId, IngestOptions options) {
        List<DatapointsCollection> collections = new ArrayList<>(datapointsByExternalId.size());
        datapointsByExternalId.forEach((externalId, points) -> {
            DatapointsCollection collection = new DatapointsCollection();
            collection.setExternalId(externalId);
            List<DatapointString> wire = new ArrayList<>(points.size());
            for (Datapoint point : points) {
                wire.add(point.toDatapointString());
            }
            collection.setDatapoints(wire);
            collections.add(collection);
        });
        return ingest(collections, options);
    }
}
