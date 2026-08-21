-- One ClickHouse database per demo tenant (foo, bar).
--
-- The clickhouse-server image runs every *.sql here on first initialisation of the
-- data volume. The datahub-stateless-consumer writes time-series into these; each
-- tenant routes to its own database (see scripts/vault-seed.sh). IF NOT EXISTS keeps
-- this safe to re-run.
CREATE DATABASE IF NOT EXISTS foo;
CREATE DATABASE IF NOT EXISTS bar;
