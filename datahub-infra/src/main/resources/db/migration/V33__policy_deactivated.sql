-- Deactivation as a column on `node`, replacing the metadata key policies were using for it.
--
-- A policy is switched off rather than deleted: it is a node other things point at and its
-- findings outlive it, so removing it to stop it applying discards the record of what it decided.
-- That state was first stored as a `deactivated` metadata row, which worked but made it one string
-- among a policy's configuration values rather than a property of the node, and meant the naming
-- policy resolver had to load a policy's metadata before it could tell whether the policy counted.
--
-- The column is on `node` because a policy IS a node (PolicyEntity extends NodeEntity, node_type
-- POLICY) and there is no policy table to put it on. Nothing else sets it today; other node types
-- default to active and are unaffected.
ALTER TABLE node
    ADD COLUMN IF NOT EXISTS is_deactivated boolean NOT NULL DEFAULT false;

-- Carry over anything already switched off through the metadata key, or upgrading would quietly
-- put every deactivated policy back into force. Only an explicit 'true' counts, matching how the
-- key was read.
UPDATE node n
   SET is_deactivated = true
  FROM node_metadata m
 WHERE m.node_id = n.id
   AND m.key = 'deactivated'
   AND lower(m.value) = 'true';

-- The key has no readers left once the column exists, and leaving it would be a second place the
-- same fact is recorded, free to disagree with the column the next time one is written without the
-- other.
DELETE FROM node_metadata
 WHERE key = 'deactivated';

-- Partial index: the queries that care ask for the policies still in force, and the deactivated
-- ones are the rare rows. Indexing only them keeps it small while still serving that filter.
CREATE INDEX IF NOT EXISTS node_is_deactivated_idx
    ON node (id)
 WHERE is_deactivated;
