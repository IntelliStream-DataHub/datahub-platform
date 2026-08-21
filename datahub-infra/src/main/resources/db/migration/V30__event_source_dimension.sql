-- Distinct-value dimension table for event source (sibling of the type / sub_type / status tables
-- introduced in V29 — see that migration for the full design rationale).
--
-- Holds only the DISTINCT (data_set_id, source) pairs seen across events, so /events/list/sources and
-- /events/search/source can enumerate and substring-search the source vocabulary without scanning the
-- billion-row ClickHouse events table. Maintained on the write path with INSERT ... ON CONFLICT DO
-- NOTHING (idempotent) and rebuilt by the weekly ClickHouse reconciliation job, exactly like the V29
-- tables. data_set_id = 0 is the "no dataset" sentinel, so there is deliberately no foreign key.
--
-- Split into its own migration (rather than folded into V29) because V29 has already been applied;
-- editing it would break Flyway's checksum validation.
--
-- If the read-all substring path ever gets slow, accelerate ILIKE '%q%' with a trigram GIN index
-- (requires CREATE EXTENSION pg_trgm — elevated privilege, once per tenant database):
--
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX IF NOT EXISTS event_source_dim_value_trgm ON event_source_dim USING gin (source gin_trgm_ops);

CREATE TABLE IF NOT EXISTS event_source_dim (
    data_set_id BIGINT NOT NULL,
    source      TEXT   NOT NULL,
    PRIMARY KEY (data_set_id, source)
);
