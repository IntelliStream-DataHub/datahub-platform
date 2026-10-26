-- Agents (intellistream-flow): a flow whose steps include the agent processor is listed and shown
-- apart from other flows, by its kind. The definition carries it ("kind": "agent") and the service
-- copies it here when the flow is saved, so lists filter on a column rather than inside the JSON.
ALTER TABLE flow ADD COLUMN kind text NOT NULL DEFAULT 'flow' CHECK (kind IN ('flow', 'agent'));
