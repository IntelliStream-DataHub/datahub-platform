-- How much memory a run may hold is no longer the flow's to say: every run on an instance gets an
-- equal share of that instance's run memory budget (datahub-flow's FlowConfig), so neither the
-- deployment nor the run carries a size of its own any more.
ALTER TABLE flow.flow DROP COLUMN memory_bytes;
ALTER TABLE flow.run DROP COLUMN memory_bytes;
