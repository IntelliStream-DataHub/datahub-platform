-- Distinct-value dimension tables for event type / sub_type / status.
--
-- Events themselves live in ClickHouse (+ KVRocks for the externalId->id map); these small Postgres
-- tables hold only the DISTINCT (data_set_id, value) pairs seen across events, so the
-- /events/list/{types,sub-types,statuses} endpoints can enumerate and substring-search the
-- categorical vocabularies without scanning the billion-row ClickHouse events table.
--
-- Maintained on the write path: EventService.create()/update() does an
-- INSERT ... ON CONFLICT DO NOTHING per (data_set_id, value) — idempotent, so Pulsar redeliveries
-- and retries are harmless. ON CONFLICT only ever *adds*; a value whose last event is deleted (or
-- whose type is changed) lingers until the weekly ClickHouse reconciliation job rebuilds the table.
--
-- Keyed by data_set_id so reads apply the same dataset ACL used by /events/filter and /events/search
-- (data_set_id IN <readable>). data_set_id = 0 is the "no dataset" sentinel (matching ClickHouse), so
-- there is deliberately NO foreign key to data_set — a 0 row must be allowed, and event creation must
-- never fail because of this derived index.
--
-- The primary key (data_set_id, value) is both the ON CONFLICT arbiter and the btree that backs the
-- ACL range scan. Substring search uses a plain ILIKE '%q%' over the (already ACL-narrowed) rows,
-- which is cheap at this table's size (datasets x small cardinality).
--
-- If the read-all substring path ever gets slow, accelerate ILIKE '%q%' with trigram GIN indexes.
-- This is intentionally left commented out because CREATE EXTENSION requires elevated DB privileges
-- (no existing migration uses it) and must run once per tenant database. Exact commands:
--
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX IF NOT EXISTS event_type_dim_value_trgm     ON event_type_dim     USING gin (type     gin_trgm_ops);
--   CREATE INDEX IF NOT EXISTS event_sub_type_dim_value_trgm ON event_sub_type_dim USING gin (sub_type gin_trgm_ops);
--   CREATE INDEX IF NOT EXISTS event_status_dim_value_trgm   ON event_status_dim   USING gin (status   gin_trgm_ops);

CREATE TABLE IF NOT EXISTS event_type_dim (
    data_set_id BIGINT NOT NULL,
    type        TEXT   NOT NULL,
    PRIMARY KEY (data_set_id, type)
);

CREATE TABLE IF NOT EXISTS event_sub_type_dim (
    data_set_id BIGINT NOT NULL,
    sub_type    TEXT   NOT NULL,
    PRIMARY KEY (data_set_id, sub_type)
);

CREATE TABLE IF NOT EXISTS event_status_dim (
    data_set_id BIGINT NOT NULL,
    status      TEXT   NOT NULL,
    PRIMARY KEY (data_set_id, status)
);
