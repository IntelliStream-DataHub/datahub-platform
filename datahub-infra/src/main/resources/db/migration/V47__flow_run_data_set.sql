-- The dataset of a run that has no flow to take one from: a preview of a definition that was never
-- saved. Who may see a run, its records and its live view is decided by its dataset, and such a run
-- would otherwise have none, so nobody could follow the preview they just started. Null for every
-- other run, which takes its flow's dataset.
ALTER TABLE flow.run ADD COLUMN data_set_id bigint;
