-- Single-precision float value type. Backed by the datapoints_float32 table in clickhouse.sql
-- (ClickHouse Float32). See TimeseriesValueType.java for the id<->table mapping and
-- DatapointValueCodec for the 4-byte little-endian encoding.
INSERT INTO timeseries_value_type (id, name, description) VALUES
    (7, 'FLOAT32',
     'IEEE-754 single-precision FLOAT (a binary float, not an exact decimal). ClickHouse Float32 in '
  || 'table datapoints_float32 (4 bytes, Gorilla codec) — half the storage of FLOAT (Float64) with '
  || 'the same binary rounding behaviour but fewer significant digits (~7). Use for analog/continuous '
  || 'measurements where the extra precision of Float64 is not needed; choose FLOAT for wider range or '
  || 'NUMERIC / DECIMAL32 when values must be exact.');
