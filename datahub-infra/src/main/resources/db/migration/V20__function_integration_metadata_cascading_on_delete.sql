ALTER TABLE node ADD COLUMN model_name varchar(128);

COMMENT ON COLUMN node.model_name IS
    'FunctionTemplate model identifier for FunctionEntity rows only (node_type = 3). NULL for every other node type.';

-- The column lives on the shared `node` table but is meaningful only
-- for FunctionEntity rows (node_type = 3); every non-function row
-- keeps it NULL. Named `function_config` to make the ownership obvious
-- to anyone running ad-hoc queries.
ALTER TABLE node ADD COLUMN function_config jsonb;

COMMENT ON COLUMN node.function_config IS
    'JSON config blob for FunctionEntity rows only (node_type = 3). NULL for every other node type.';

-- ───────────────────────────────────────────────────────────────────
-- From V19: make edge_metadata / node_metadata cascade on parent
-- deletion. The original V1 schema declared the FKs without
-- `ON DELETE CASCADE`, so deleting any edge or node that carries
-- metadata rows fails with constraint `edge_data_edge_id_fk` /
-- `node_data_node_id_fk`. The @OnDelete(CASCADE) annotations on
-- EdgeEntity / NodeEntity expect cascading at the DB level, but
-- they only emit cascade DDL when Hibernate generates DDL — and we
-- run with ddl-auto=none (Flyway owns the schema), so the
-- annotations have been decorative.
-- ───────────────────────────────────────────────────────────────────
ALTER TABLE edge_metadata
    DROP CONSTRAINT edge_data_edge_id_fk;
ALTER TABLE edge_metadata
    ADD CONSTRAINT edge_data_edge_id_fk
        FOREIGN KEY (edge_id) REFERENCES edge(id)
            ON DELETE CASCADE;

ALTER TABLE node_metadata
    DROP CONSTRAINT node_data_node_id_fk;
ALTER TABLE node_metadata
    ADD CONSTRAINT node_data_node_id_fk
        FOREIGN KEY (node_id) REFERENCES node(id)
            ON DELETE CASCADE;


-- Pulsar subscription type used by SubscriptionWebSocketHandler when a client connects.
-- FAILOVER (default): one active consumer at a time, others stand by — the right semantics
--                     for human-facing watchers where two browser tabs both want the full stream.
-- KEY_SHARED:         broker partitions the message stream by orderingKey across all attached
--                     consumers, sticky per key. Set on system-managed function-binding subs so
--                     N worker processes per model split the load with per-timeseries ordering.
--
-- This is internal-only; the user-facing /subscriptions/create endpoint does not expose the
-- field and always lands rows as FAILOVER. System-managed paths
-- (FunctionBindingLifecycleHandler) write KEY_SHARED.
ALTER TABLE subscription
    ADD COLUMN subscription_type varchar(32) NOT NULL DEFAULT 'FAILOVER';

COMMENT ON COLUMN subscription.subscription_type IS
    'Pulsar subscription type used by the WS handler: FAILOVER for human-facing watchers, '
    'KEY_SHARED for system-managed function-worker subs.';


ALTER TABLE subscription
    ADD COLUMN system_managed boolean NOT NULL DEFAULT false;
-- FunctionEntity has mapped a `model_name` column on the shared `node` table for
-- as long as the entity has existed, but V17 only added `function_config`. On any
-- environment that ran the migrations as written, Hibernate boots with the entity
-- referencing a non-existent column and every Function CRUD path fails. Add the
-- column now; like `function_config` it lives on `node` (single-table inheritance)
-- and stays NULL for non-function rows (node_type != 3).
