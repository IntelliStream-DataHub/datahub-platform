-- Flow runs are no longer kept in Postgres: while they are queued, running or just finished they
-- are in the tenant's KVRocks, and once they have ended in its ClickHouse (flow_run). See
-- RUN_STORE.md. This drops what V46 and V49 made for them: the table, with its indexes and its
-- storage settings. Flows, versions, deployments and schedules stay here.
DROP TABLE IF EXISTS flow.run;
