// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.client;

import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.sdk.auth.TokenProvider;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.sdk.ingest.DatapointSpool;
import ai.intellistream.datahub.sdk.ingest.DurableSpool;
import ai.intellistream.datahub.sdk.services.DatasetService;
import ai.intellistream.datahub.sdk.services.EdgeService;
import ai.intellistream.datahub.sdk.services.EventService;
import ai.intellistream.datahub.sdk.services.FileService;
import ai.intellistream.datahub.sdk.services.ResourceService;
import ai.intellistream.datahub.sdk.services.SubscriptionService;
import ai.intellistream.datahub.sdk.services.TimeseriesService;
import ai.intellistream.datahub.sdk.services.UnitService;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * Entry point to the DataHub SDK. Owns a shared {@link HttpClient}, JSON mapper and token
 * provider, and exposes the per-area services. Thread-safe — create one and share it.
 */
public final class DatahubClient {

    private final HttpClient http;
    private final JsonMapper mapper;
    private final TokenProvider tokenProvider;
    private final ApiHttp api;

    private final ResourceService resources;
    private final EdgeService edges;
    private final TimeseriesService timeseries;
    private final DatasetService datasets;
    private final EventService events;
    private final UnitService units;
    private final FileService files;
    private final SubscriptionService subscriptions;

    public DatahubClient(DatahubConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    /**
     * Build a client that REUSES a caller-supplied {@link HttpClient} (its connection pool / executor)
     * instead of creating its own. Lets a server keep one shared {@link HttpClient} and build a cheap
     * per-request client bound to a per-request static token (via the config builder's {@code token(..)}).
     */
    public DatahubClient(DatahubConfig config, HttpClient http) {
        this.http = http;
        // NodeModelSubtypes makes NodeModel-typed reads dispatch on the type-label.
        this.mapper = JsonMapper.builder().addModule(new NodeModelSubtypes()).build();
        this.tokenProvider = new TokenProvider(config, http, mapper);
        this.api = new ApiHttp(config.baseUrl(), http, mapper, tokenProvider);

        this.resources = new ResourceService(api);
        this.edges = new EdgeService(api);
        this.datasets = new DatasetService(api);
        this.units = new UnitService(api);
        this.files = new FileService(api);
        this.subscriptions = new SubscriptionService(api);

        // Durable ingest buffering (off unless the config opts in). Datapoints and events each get
        // their own spool file under the buffer directory, sharing the same retention bounds.
        if (config.hasBuffering()) {
            Path dir = config.bufferDirectory() != null ? config.bufferDirectory() : Path.of(".datahub-spool");
            // Each stream gets its own subdirectory of gzip-NDJSON segments under the buffer directory.
            DatapointSpool datapointSpool = new DatapointSpool(
                    dir.resolve("datapoints"), config.bufferRetention(), config.bufferMaxBytes(), mapper);
            DurableSpool<EventModel> eventSpool = new DurableSpool<>(
                    dir.resolve("events"), config.bufferRetention(), config.bufferMaxBytes(), mapper,
                    EventModel.class, event -> event.getEventTime().toInstant().toEpochMilli());
            this.timeseries = new TimeseriesService(api, datapointSpool);
            this.events = new EventService(api, eventSpool);
        } else {
            this.timeseries = new TimeseriesService(api);
            this.events = new EventService(api);
        }
    }

    /** Build a client from explicit configuration. */
    public static DatahubClient create(DatahubConfig config) {
        return new DatahubClient(config);
    }

    /** Build a client that reuses a shared {@link HttpClient} (see {@link #DatahubClient(DatahubConfig, HttpClient)}). */
    public static DatahubClient create(DatahubConfig config, HttpClient http) {
        return new DatahubClient(config, http);
    }

    /** Build a client from the environment (and an optional {@code .env} file). */
    public static DatahubClient fromEnv() {
        return new DatahubClient(DatahubConfig.fromEnv());
    }

    public ResourceService resources() {
        return resources;
    }

    public EdgeService edges() {
        return edges;
    }

    public TimeseriesService timeseries() {
        return timeseries;
    }

    public DatasetService datasets() {
        return datasets;
    }

    public EventService events() {
        return events;
    }

    public UnitService units() {
        return units;
    }

    public FileService files() {
        return files;
    }

    public SubscriptionService subscriptions() {
        return subscriptions;
    }
}
