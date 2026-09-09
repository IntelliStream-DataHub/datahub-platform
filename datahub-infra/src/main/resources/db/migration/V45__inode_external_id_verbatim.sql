-- Files move to the platform's verbatim external-id model.
--
-- Until now the inode family kept its own scheme: INode.setExternalId rewrote the caller's value to
-- a lowercase slug and hashed the rewritten string, so file external ids never round-tripped while
-- every other entity's did after the naming-policy change. Identity now comes from
-- ExternalIds.hash (lowercase, then XXH3) on both write and read, and external_id stores what the
-- caller sent.
--
-- LIVE ROWS NEED NO REHASH. Every existing external_id was produced by the old slug rewrite, so it
-- is already lowercase, and hash(lower(x)) == hash(x) for all of them. Only the soft-delete
-- tombstones differ, and V46 rewrites those in Java (XXH3 is not a Postgres function).
--
-- ---------------------------------------------------------------------------------------------
-- Uniqueness: live rows only
-- ---------------------------------------------------------------------------------------------
--
-- moveNodeToTrash renames a deleted node to DELETED_<checksum>_<originalId>_<epochMillis>, so a
-- new file can take the freed external id. The uppercase prefix was doing more than labelling:
-- because the old rewrite lowercased every caller-supplied id, no user could create a value
-- starting with an uppercase DELETED_, which is what kept a tombstone from colliding with a live
-- node under the table-wide unique constraint.
--
-- ExternalIds.hash case-folds, so that guarantee is gone: `deleted_a1b2_report_1758000000000` is an
-- id a caller may now legitimately send, and it would hash to the same value as the tombstone.
-- Excluding deleted rows from uniqueness replaces a guarantee that rested on casing with one that
-- rests on the flag that actually means "this is a tombstone". Two tombstones may now share a hash,
-- which is harmless -- they are not addressable as live nodes, and restore refuses when the original
-- external id has been taken.
ALTER TABLE inodes DROP CONSTRAINT IF EXISTS inode_external_id_hash_key;

CREATE UNIQUE INDEX IF NOT EXISTS inode_external_id_hash_live_key
    ON inodes (external_id_hash)
    WHERE is_deleted = false;
