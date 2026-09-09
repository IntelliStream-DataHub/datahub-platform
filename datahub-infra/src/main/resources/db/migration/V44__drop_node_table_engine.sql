-- TimeseriesEntity.tableEngine is gone. The column held which storage engine backs a series,
-- but only one value was ever written: NodeService hardcoded MERGETREE on create, no update
-- path touched it, and nothing read it back — not the REST contract (retired from the wire;
-- bodies that still send it are tolerated as a legacy field), not ClickHouse routing, not a
-- query or filter. A column with one possible value and no reader documents nothing.
-- Safe to drop unconditionally: added nullable in V14, and no index, constraint or view
-- references it. The graph mirror's tableEngine property stops being projected in the same
-- change and disappears from each node on its next write, since the applier assigns a node's
-- properties wholesale.
ALTER TABLE node
    DROP COLUMN table_engine;
