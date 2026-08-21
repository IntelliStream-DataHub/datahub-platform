-- Extend full-text search to cover external_id and description, not just name.
--
-- The node search queries (resources, datasets, assets, policies, timeseries and the generic node
-- search) now match against a combined tsvector of name + external_id + description. This GIN index
-- backs that combined expression so the match does not require a sequential scan.
--
-- Notes:
--  * 'simple' config matches the search queries and the other FTS indexes already in this schema
--    (node_name_idx, node_description_idx, data_set_name_idx, ...). The previous queries used the
--    unqualified to_tsvector(name) (default config), which never matched the 'simple' indexes, so
--    those single-column indexes were effectively unused for search.
--  * coalesce() guards NULLs: concatenating a NULL description/external_id with || would otherwise
--    null the whole vector and drop the row from results even when the name matched.
CREATE INDEX IF NOT EXISTS node_fts_idx
    ON node
    USING GIN (to_tsvector('simple',
        coalesce(name, '') || ' ' || coalesce(external_id, '') || ' ' || coalesce(description, '')));

-- One combined index is enough for node search. The single-column FTS indexes below are now
-- superseded by node_fts_idx, and were already unused by the previous search queries (which used
-- the default-config to_tsvector(...) and so never matched these 'simple' indexes). Drop them so
-- node does not carry redundant GIN indexes that add write + storage overhead on every insert.
DROP INDEX IF EXISTS node_name_idx;
DROP INDEX IF EXISTS node_description_idx;
