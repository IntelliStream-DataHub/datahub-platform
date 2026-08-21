-- Mixed numeric+text value type for sensors that emit both decimal readings and text statuses in
-- one series. Backed by datapoints_mixed (two typed columns: value_numeric Float64 + value_text)
-- in clickhouse.sql. See TimeseriesValueType (getTableType + the *ValueSql helpers) for how reads
-- coalesce the columns and aggregate only the numeric one.
INSERT INTO timeseries_value_type (id, name, description) VALUES
    (6, 'MIXED',
     'Mixed numeric + text series, for a sensor that emits both decimal readings and text statuses '
  || '(e.g. 23.5, 23.6, "FAULT", 23.4). Stored in datapoints_mixed as two typed ClickHouse columns '
  || '— value_numeric Nullable(Float64) and value_text — each row using exactly one. Aggregates '
  || '(SUM/MIN/MAX/AVG) run on the numeric column and skip text rows automatically; raw reads '
  || 'return the value as a string (number or text). Prefer this over a single Dynamic column: it '
  || 'aggregates natively, compresses better (per-column codecs), and encodes simply.');
