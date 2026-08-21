-- Create one Postgres database per demo tenant (foo, bar).
--
-- The postgres docker-entrypoint runs every *.sql here exactly once, the first
-- time the data volume is initialised (an empty pgdata). To re-run after editing,
-- recreate the volume: `podman compose down -v` (or `docker compose down -v`).
--
-- This only makes the databases exist. The schema objects inside each are created
-- by Flyway on datahub-api startup (datahub.flyway.per-tenant-migrate=true), which
-- migrates every tenant's database but does not create the database itself.
--
-- CREATE DATABASE has no IF NOT EXISTS, so guard with \gexec to stay idempotent if
-- this file is ever applied by hand.
SELECT 'CREATE DATABASE foo OWNER foobar'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'foo')\gexec

SELECT 'CREATE DATABASE bar OWNER foobar'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bar')\gexec
