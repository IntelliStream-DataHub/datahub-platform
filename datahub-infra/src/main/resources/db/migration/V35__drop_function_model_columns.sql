-- Function is now a plain datastore node (like a resource): it carries only the shared
-- `node` columns. Drop the two ML-only columns that FunctionEntity used to map.
-- Existing function rows (node_type = 3) keep their node row and every common field;
-- they lose the model_name / function_config values, which is the intended semantic.
-- IF EXISTS makes the drop safe on environments where a prior partial migration left
-- the columns absent.
ALTER TABLE node DROP COLUMN IF EXISTS model_name;
ALTER TABLE node DROP COLUMN IF EXISTS function_config;
