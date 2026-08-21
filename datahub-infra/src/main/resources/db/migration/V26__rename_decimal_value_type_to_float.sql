-- Value type id 2 stores its datapoints as Float64 (table datapoints_float), but it was named
-- DECIMAL and accepted both "FLOAT" and "DECIMAL" at create time. "decimal" wrongly implied an exact
-- decimal type (that is NUMERIC / DECIMAL32). Rename the canonical name to FLOAT so the only way to
-- create a Float64 series is "FLOAT"; TimeseriesValueType.getValueTypeId no longer accepts "DECIMAL".
-- Existing series keep value_type_id 2 and their data in datapoints_float — only the name changes.
UPDATE timeseries_value_type SET name = 'FLOAT' WHERE id = 2;

UPDATE timeseries_value_type SET description =
    'IEEE-754 double-precision FLOAT (a binary float, not an exact decimal). ClickHouse Float64 in '
 || 'table datapoints_float (Gorilla codec, tuned for slowly-changing time-series floats). Fast to '
 || 'aggregate but carries binary rounding error (0.1 is not exact). Use for analog/continuous '
 || 'measurements where speed matters and tiny rounding is acceptable; choose NUMERIC or DECIMAL32 '
 || 'when values must be exact.'
 WHERE id = 2; -- FLOAT
