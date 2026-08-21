-- Optimistic-lock counter for NodeEntity (and all single-table-inheritance subtypes: asset,
-- dataset, timeseries, resource, policy, function). Hibernate emits
--   UPDATE node SET ..., version = version + 1 WHERE id = ? AND version = ?
-- on every dirty-check save, and treats "0 rows affected" as a concurrency failure. This
-- is what turns a concurrent update-vs-delete (or update-vs-update) into a surfaced 409
-- instead of a silent lost write plus a phantom CUD event downstream.
--
-- DEFAULT 0 lets existing rows seed the counter without a backfill; new inserts get 0 too
-- and increment from there. NOT NULL so Hibernate can trust the column is always present.
ALTER TABLE node ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
