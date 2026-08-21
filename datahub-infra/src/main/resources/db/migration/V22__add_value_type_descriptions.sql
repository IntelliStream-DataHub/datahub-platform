-- Document the timeseries value types.
--
-- A timeseries' value_type is chosen at creation and is immutable for its lifetime: it fixes which
-- physical ClickHouse table (and therefore which storage type, precision, exactness and compression
-- codec) every datapoint of that series lands in. The id<->ClickHouse-type mapping lives in
-- TimeseriesValueType.java (getTableType) and the encoders in ClickHouseHelper. This column exists so
-- the trade-offs are discoverable from the database itself rather than reverse-engineered from code.

ALTER TABLE timeseries_value_type ADD COLUMN description text;

UPDATE timeseries_value_type SET description =
    '64-bit signed integer. ClickHouse Int64 in table datapoints_bigint (T64 codec, which is very '
 || 'effective for bounded-range integers). Use for whole-number measurements and counters; it has '
 || 'no fractional part, so pick a decimal/float type if you need decimals.'
 WHERE id = 1; -- BIGINT

UPDATE timeseries_value_type SET description =
    'IEEE-754 double-precision FLOAT (NOT an exact decimal, despite the name). ClickHouse Float64 in '
 || 'table datapoints_float (Gorilla codec, tuned for slowly-changing time-series floats). Fast to '
 || 'aggregate but carries binary rounding error (0.1 is not exact). Use for analog/continuous '
 || 'measurements where speed matters and tiny rounding is acceptable; choose NUMERIC or DECIMAL32 '
 || 'when values must be exact.'
 WHERE id = 2; -- DECIMAL

UPDATE timeseries_value_type SET description =
    'Exact fixed-point decimal with 8 fractional digits. ClickHouse Decimal64(8) in table '
 || 'datapoints_numeric (8 bytes, T64 codec). No rounding error and a large magnitude range (~10 '
 || 'integer digits). Costs more storage and slower aggregation than Float64. Use when exactness '
 || 'matters (billing, financial, high-precision); for low-magnitude low-precision values prefer '
 || 'DECIMAL32, which is half the size.'
 WHERE id = 3; -- NUMERIC

UPDATE timeseries_value_type SET description =
    'Arbitrary string values. ClickHouse LowCardinality(String) in table datapoints_text. Use for '
 || 'categorical / non-numeric series (equipment states, modes, status labels). LowCardinality makes '
 || 'repeated values cheap to store and fast to filter; avoid it for high-cardinality free text.'
 WHERE id = 4; -- TEXT
