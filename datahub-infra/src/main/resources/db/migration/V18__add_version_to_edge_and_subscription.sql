-- Mirror of V17 for edge and subscription. Hibernate needs the @Version column present and
-- NOT NULL on every row so the version-check WHERE clause never matches nothing on an
-- otherwise-valid UPDATE. DEFAULT 0 seeds existing rows without a backfill step.
--
-- See V17__add_version_to_node.sql for the full rationale.
ALTER TABLE edge ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subscription ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
