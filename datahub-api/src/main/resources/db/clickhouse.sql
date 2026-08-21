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