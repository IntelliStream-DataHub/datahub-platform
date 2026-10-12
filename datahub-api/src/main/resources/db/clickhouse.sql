CREATE OR REPLACE TABLE events (
                                    id                                   UUID CODEC(ZSTD(9)),
                                    external_id                          LowCardinality(String) CODEC(ZSTD(9)),
                                    external_id_hash                     Int128 CODEC(ZSTD(9)),
                                    type                                 LowCardinality(String) CODEC(ZSTD(9)),
                                    sub_type                             Nullable(String) CODEC(ZSTD(9)),
                                    status                               Nullable(String) CODEC(ZSTD(9)),
                                    description                          String CODEC(ZSTD(9)),
                                    data_set_id                          Int64 CODEC(DoubleDelta, ZSTD(9)),
                                    source                               LowCardinality(String) CODEC(ZSTD(9)),
                                    -- Date times for when created and updated in CH
                                    date_created                         DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)), -- Using DateTime64 with precision for milliseconds and explicit time zone
                                    last_updated                         DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
                                    -- Event date times
                                    event_time                           DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
                                    related_resources_id                 Array(Int64) CODEC(DoubleDelta, ZSTD(9)),
                                    related_resources_external_id        Array(LowCardinality(String)) CODEC(ZSTD(9)),
                                    related_resources_external_id_hash   Array(Int64) CODEC(T64, ZSTD(9)),
                                    metadata                             Map(LowCardinality(String), String) CODEC(ZSTD(9)),
                                    INDEX events_external_id_idx external_id TYPE ngrambf_v1(3, 1024, 5, 0) GRANULARITY 8,
                                    INDEX event_external_id_hash (external_id_hash) TYPE bloom_filter(0.001) GRANULARITY 8,
                                    INDEX event_type_idx type TYPE set(0) GRANULARITY 8,
                                    INDEX event_sub_type_idx sub_type TYPE set(0) GRANULARITY 8,
                                    INDEX event_status_idx status TYPE set(0) GRANULARITY 8,
                                    INDEX event_event_time_idx (event_time) TYPE minmax GRANULARITY 16,
                                    INDEX event_related_resources_id_idx related_resources_id TYPE bloom_filter GRANULARITY 8,
                                    INDEX event_related_resources_external_id_hash_idx related_resources_external_id_hash TYPE bloom_filter GRANULARITY 8,
                                    INDEX events_metadata_keys_idx mapKeys(metadata) TYPE bloom_filter GRANULARITY 8,
                                    INDEX events_metadata_values_idx mapValues(metadata) TYPE bloom_filter GRANULARITY 8
) ENGINE = ReplacingMergeTree
      ORDER BY id
    PRIMARY KEY id
      PARTITION BY (toYYYYMM(event_time))
-- low-index granularity can improve query performance by id / external_id_hash but can decrease insert performance
      SETTINGS index_granularity = 2048;

CREATE OR REPLACE TABLE datapoints_bigint (
       timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)),
       timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
       value Int64 CODEC(T64, ZSTD(9))
)
    engine=ReplacingMergeTree
    order by (timeseries_id, timestamp)
    PRIMARY KEY (timeseries_id, timestamp)
    PARTITION BY YEAR(timestamp)
    -- YEAR(timestamp) partitions are one ever-growing bucket for the whole year, continuously fed
    -- by ~250ms streaming inserts. Force-merge parts once they're old enough that the normal
    -- size-based merge selector would otherwise leave them fragmented, so aggregate queries (which
    -- build a much bigger query plan per part than a raw scan) don't blow past ClickHouse's
    -- query_plan_max_optimizations_to_apply on recent data. Left at the per-part-range default
    -- (min_age_to_force_merge_on_partition_only = false) rather than whole-partition, since forcing
    -- an entire year's partition into one part would be a huge, unnecessary merge.
    SETTINGS min_age_to_force_merge_seconds = 300;

CREATE OR REPLACE TABLE datapoints_float (
                                           timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)),
                                           timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
                                           value Float64 CODEC(Gorilla, ZSTD(9))
)
    engine=ReplacingMergeTree
        order by (timeseries_id, timestamp)
        PRIMARY KEY (timeseries_id, timestamp)
        PARTITION BY YEAR(timestamp)
        -- see datapoints_bigint above for why
        SETTINGS min_age_to_force_merge_seconds = 300;

-- Single-precision float for low-precision series that don't need Float64's range/precision.
-- ClickHouse Float32 (4 bytes, half of datapoints_float) with the Gorilla codec. Same binary-float
-- rounding caveat as Float64, just fewer significant digits (~7). See value_type id 7.
CREATE OR REPLACE TABLE datapoints_float32 (
                                           timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)),
                                           timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
                                           value Float32 CODEC(Gorilla, ZSTD(9))
)
    engine=ReplacingMergeTree
        order by (timeseries_id, timestamp)
        PRIMARY KEY (timeseries_id, timestamp)
        PARTITION BY YEAR(timestamp)
        -- see datapoints_bigint above for why
        SETTINGS min_age_to_force_merge_seconds = 300;

CREATE OR REPLACE TABLE datapoints_numeric (
                                             timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)),
                                             timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
                                             value Decimal64(6) CODEC(T64, ZSTD(9))
)
    engine=ReplacingMergeTree
        order by (timeseries_id, timestamp)
        PRIMARY KEY (timeseries_id, timestamp)
        PARTITION BY YEAR(timestamp)
        -- see datapoints_bigint above for why
        SETTINGS min_age_to_force_merge_seconds = 300;

-- Compact (4-byte) exact decimal for low-precision series. Decimal32(4) holds 9 significant digits
-- (magnitude +/-99999.9999); the producer rounds to 4 decimals half-up and clamps out-of-range
-- magnitudes. T64 codec compresses the scaled-integer storage very well. See value_type id 5.
CREATE OR REPLACE TABLE datapoints_decimal32 (
                                             timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)),
                                             timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
                                             value Decimal32(4) CODEC(T64, ZSTD(9))
)
    engine=ReplacingMergeTree
        order by (timeseries_id, timestamp)
        PRIMARY KEY (timeseries_id, timestamp)
        PARTITION BY YEAR(timestamp)
        -- see datapoints_bigint above for why
        SETTINGS min_age_to_force_merge_seconds = 300;

CREATE OR REPLACE TABLE datapoints_text (
                                              timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)),
                                              timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)),
                                              value LowCardinality(String) CODEC(ZSTD(9))
)
    engine=ReplacingMergeTree
        order by (timeseries_id, timestamp)
        PRIMARY KEY (timeseries_id, timestamp)
        PARTITION BY YEAR(timestamp)
        -- see datapoints_bigint above for why
        SETTINGS min_age_to_force_merge_seconds = 300;

-- Mixed numeric+text series (value type MIXED): for a sensor that emits both decimal readings and
-- text status. Each row populates exactly ONE column; the other is NULL. Aggregates run on
-- value_numeric (text rows are NULL there and drop out of SUM/MIN/MAX/AVG); raw reads coalesce the
-- two columns. Float64 + Gorilla for the numeric side; the mostly-NULL column compresses to almost
-- nothing. (Two typed columns beat a single ClickHouse Dynamic column here: native aggregation,
-- per-column codecs, and a simple RowBinary encoding.)
CREATE OR REPLACE TABLE datapoints_mixed (
    timeseries_id Int64                            CODEC(DoubleDelta, ZSTD(9)),
    timestamp     DateTime64(3, 'UTC')             CODEC(DoubleDelta, ZSTD(9)),
    value_numeric Nullable(Float64)                CODEC(Gorilla, ZSTD(9)),
    value_text    LowCardinality(Nullable(String)) CODEC(ZSTD(9))
)
    engine=ReplacingMergeTree
        order by (timeseries_id, timestamp)
        PRIMARY KEY (timeseries_id, timestamp)
        PARTITION BY YEAR(timestamp)
        -- see datapoints_bigint above for why
        SETTINGS min_age_to_force_merge_seconds = 300;
-- What happened in each flow run (intellistream-flow): one row per action a processor took on an
-- item through its session. Append-only. Rows arrive through the tenant's Pulsar topic
-- <tenant>/flow/records and are inserted per batch with an insert_deduplication_token derived from
-- run, attempt and sequence, so a batch redelivered after an insert is not stored twice; that token
-- only takes effect because of non_replicated_deduplication_window below.
--
-- transit_uri says where an item came from or went to. For DataHub's own data it has a fixed form,
-- datahub://timeseries/<externalId>?from=<instant>&to=<instant> or datahub://events/<externalId>,
-- and the transit_* columns are derived from it by ClickHouse when a row is written, never written
-- by anyone. The projection serves "which run last wrote this series at this time".
--
-- Expires with the tenant's flow retention (90 days by default); changing it is an
-- ALTER TABLE flow_run_event MODIFY TTL on that tenant's table.
CREATE OR REPLACE TABLE flow_run_event (
    event_time           DateTime64(3, 'UTC')   CODEC(DoubleDelta, ZSTD(9)),
    run_id               UUID,
    attempt              UInt16,
    -- The batch within the run attempt this row was published in, and the row's position in it:
    -- records of one invocation share their time, so (attempt, sequence, ordinal) orders a run.
    sequence             UInt32,
    ordinal              UInt32,
    flow_external_id     LowCardinality(String),
    flow_version         UInt32,
    -- The processor's id in the definition, and its type.
    processor            LowCardinality(String),
    processor_type       LowCardinality(String),
    -- create, fork, receive, send, attributes_modified, content_modified, route, clone, drop; and
    -- truncated for the marker row that ends an attempt which hit the per-run record cap.
    action               LowCardinality(String),
    item_id              UUID,
    parent_item_ids      Array(UUID),
    relationship         LowCardinality(String),
    -- timeseries, events, records, json, bytes.
    content_kind         LowCardinality(String),
    size                 UInt64,
    -- xxHash64 of the content's canonical form, 0 when the action did not touch content.
    hash                 UInt64,
    changed_attributes   Map(String, String),
    transit_uri          String,
    duration_ms          UInt32,
    -- Set on the marker row (action truncated) of a run attempt that hit the per-run record cap.
    truncated            Bool DEFAULT false,

    transit_scheme       LowCardinality(String) MATERIALIZED protocol(transit_uri),
    -- domain() returns nothing for a host without a dot, such as 'timeseries', hence the regex.
    transit_entity       LowCardinality(String) MATERIALIZED
        if(protocol(transit_uri) = 'datahub', extract(transit_uri, '^datahub://([^/?#]*)'), ''),
    transit_external_id  String MATERIALIZED
        if(protocol(transit_uri) = 'datahub', decodeURLComponent(substring(path(transit_uri), 2)), ''),
    transit_window_start DateTime64(3, 'UTC') MATERIALIZED
        if(protocol(transit_uri) = 'datahub',
           parseDateTime64BestEffortOrZero(decodeURLComponent(extractURLParameter(transit_uri, 'from')), 3, 'UTC'),
           toDateTime64(0, 3, 'UTC')),
    transit_window_end   DateTime64(3, 'UTC') MATERIALIZED
        if(protocol(transit_uri) = 'datahub',
           parseDateTime64BestEffortOrZero(decodeURLComponent(extractURLParameter(transit_uri, 'to')), 3, 'UTC'),
           toDateTime64(0, 3, 'UTC')),

    -- Only what the lookup returns, which is enough to find the run and its attempt; the run's
    -- details are read from Postgres. Listed rather than SELECT *: with SELECT * the projection was
    -- built but ClickHouse 26.8 never chose it for this lookup (FlowRunEventTableIT checks that it
    -- does).
    PROJECTION by_target (
        SELECT transit_entity, transit_external_id, transit_window_start, transit_window_end,
               event_time, run_id, attempt, flow_external_id, flow_version, processor, action
        ORDER BY (transit_entity, transit_external_id, transit_window_start, event_time)
    )
)
    ENGINE = MergeTree
        PARTITION BY toYYYYMM(event_time)
        ORDER BY (run_id, event_time)
        TTL toDateTime(event_time) + INTERVAL 90 DAY
        SETTINGS non_replicated_deduplication_window = 1000;
