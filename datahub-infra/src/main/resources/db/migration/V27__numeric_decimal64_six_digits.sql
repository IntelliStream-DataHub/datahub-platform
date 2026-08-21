-- NUMERIC (value type id 3) now stores ClickHouse Decimal64(6) instead of Decimal64(8): 6 fractional
-- digits is plenty for the measurement data this type holds and buys a larger integer range and
-- better T64 compression. The ClickHouse column type lives in clickhouse.sql (datapoints_numeric) and
-- the scale in ClickHouseHelper.writeNumeric6 / DatapointValueCodec. This only refreshes the
-- operator-facing description so it matches.
UPDATE timeseries_value_type SET description =
    'Exact fixed-point decimal with 6 fractional digits. ClickHouse Decimal64(6) in table '
 || 'datapoints_numeric (8 bytes, T64 codec). No rounding error and a large magnitude range (~12 '
 || 'integer digits). Costs more storage and slower aggregation than Float64. Use when exactness '
 || 'matters (billing, financial, high-precision); for low-magnitude low-precision values prefer '
 || 'DECIMAL32, which is half the size.'
 WHERE id = 3; -- NUMERIC
