#!/bin/bash
# Create the time-series + events TABLES inside each tenant's ClickHouse database.
#
# 10-create-tenant-databases.sql makes the empty foo/bar databases; this applies the
# authoritative schema (datahub-api/src/main/resources/db/clickhouse.sql, the same file
# the platform ships) into each one. Without it the stateless consumer's datapoint/event
# inserts fail with "table doesn't exist" — Postgres gets its schema from Flyway, but
# ClickHouse has no equivalent runtime migration, so we create the tables here.
#
# The clickhouse-server image runs this on first initialisation of the data volume only
# (it executes *.sh and *.sql in /docker-entrypoint-initdb.d once, against a temporary
# local server). To re-run after editing, recreate the volume: `podman compose down -v`
# (or `docker compose down -v`). The schema is mounted read-only at /schema/clickhouse.sql
# (see the clickhouse service in docker-compose.yml) so it stays the single source of truth.
#
# The table DDL uses CREATE OR REPLACE and carries no database prefix; --database routes it
# into the right tenant, so this is idempotent and reusable per tenant.
set -euo pipefail

SCHEMA="/schema/clickhouse.sql"
# Must match the databases created by 10-create-tenant-databases.sql and the tenants in
# scripts/vault-seed.sh.
TENANTS="foo bar"

client=(clickhouse-client --host 127.0.0.1 --user "${CLICKHOUSE_USER:-default}")
if [ -n "${CLICKHOUSE_PASSWORD:-}" ]; then
  client+=(--password "${CLICKHOUSE_PASSWORD}")
fi

for db in $TENANTS; do
  echo "[clickhouse-init] applying ${SCHEMA} to database '${db}'"
  "${client[@]}" --database "$db" --queries-file "$SCHEMA"
done

echo "[clickhouse-init] done; tables created in: ${TENANTS}"
