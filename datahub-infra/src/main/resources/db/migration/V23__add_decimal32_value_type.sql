-- New compact value type for low-precision timeseries. Backed by the datapoints_decimal32 table
-- in clickhouse.sql (ClickHouse Decimal32(4)). See TimeseriesValueType.java for the id<->table
-- mapping and ClickHouseHelper.writeDecimal32 for the encoding.
INSERT INTO timeseries_value_type (id, name, description) VALUES
    (5, 'DECIMAL32',
     'Compact exact fixed-point decimal with 4 fractional digits. ClickHouse Decimal32(4) in table '
  || 'datapoints_decimal32 (4 bytes, T64 codec) — half the storage of NUMERIC and the best '
  || 'compression of the numeric types, while exact to 4 decimals. Ideal for low-precision sensor '
  || 'data. Every value is rounded half-up to 4 decimals; magnitudes outside +/-99999.9999 '
  || '(Decimal32 = 9 significant digits) are clamped to the range max and a warning is logged — the '
  || 'batch is never rejected. Use NUMERIC if larger magnitudes must be stored faithfully.');
