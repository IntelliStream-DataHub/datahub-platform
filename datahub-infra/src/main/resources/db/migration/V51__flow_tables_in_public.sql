-- The flow tables move out of their own schema into public, with the rest of the tenant's tables:
-- one place to look, and one schema for the tooling and the backups to know about. SET SCHEMA takes
-- a table's indexes, constraints and owned sequence with it; the trigger refers to its function by
-- identity, so it follows the function. The function gets a name that says whose it is, since
-- public holds other tables' too.
ALTER TABLE flow.flow SET SCHEMA public;
ALTER TABLE flow.flow_version SET SCHEMA public;
ALTER FUNCTION flow.forbid_update() SET SCHEMA public;
ALTER FUNCTION public.forbid_update() RENAME TO flow_forbid_update;
DROP SCHEMA flow;
