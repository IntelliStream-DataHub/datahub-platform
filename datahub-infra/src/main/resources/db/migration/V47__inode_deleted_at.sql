-- Soft delete records a time instead of rewriting the external id.
--
-- Deleting a file used to do three things at once, because only one of them had a column:
--
--   is_deleted = true                                     -- the fact, stored properly
--   external_id -> DELETED_<checksum>_<originalId>_<ms>   -- the time, the original id, and freeing
--                                                            the id for reuse, all in one string
--
-- V45 retired the third job by scoping uniqueness to live rows, so the rename was left carrying two
-- facts that belong in columns. deleted_at holds the time; external_id is simply left alone, which
-- makes restore a single write of NULL and takes a caller-supplied string back out of the trash
-- filesystem path.
ALTER TABLE inodes ADD COLUMN IF NOT EXISTS deleted_at timestamp WITH TIME ZONE NULL;

-- Recover the deletion time already encoded in each tombstone's trailing _<epochMillis>. Rows whose
-- id does not end that way (an id rewritten by an older scheme, or hand-edited) fall back to
-- last_updated, which markDeleted touched, then date_created; the COALESCE cannot end up NULL for a
-- deleted row, which would read as "live" and resurrect the file.
UPDATE inodes
SET deleted_at = COALESCE(
        to_timestamp(NULLIF(substring(external_id FROM '_([0-9]+)$'), '')::bigint / 1000.0),
        last_updated,
        date_created,
        now())
WHERE is_deleted = true;

-- The predicate moves to the column that now carries the fact. Same index, same guarantee: a
-- tombstone holds no entry, so it cannot collide with a live node, and a restore re-inserts the row
-- into the index at exactly the moment it becomes addressable again.
DROP INDEX IF EXISTS inode_external_id_hash_live_key;

CREATE UNIQUE INDEX IF NOT EXISTS inode_external_id_hash_live_key
    ON inodes (external_id_hash)
    WHERE deleted_at IS NULL;

-- Two more indexes are predicated on the column, and DROP COLUMN would take them with it silently.
-- inodes_path_hash_active_uk (V19) is the one that matters: it makes the database authoritative for
-- "this path is taken", so two api instances racing to write the same path get a deterministic
-- constraint violation rather than one silently overwriting the other. Losing it would not fail
-- anything at migration time, it would just stop protecting.
DROP INDEX IF EXISTS inodes_path_hash_active_uk;
DROP INDEX IF EXISTS inodes_is_deleted_idx;

ALTER TABLE inodes DROP COLUMN IF EXISTS is_deleted;

CREATE UNIQUE INDEX IF NOT EXISTS inodes_path_hash_active_uk
    ON inodes (path_hash)
    WHERE deleted_at IS NULL;

-- Replaces inodes_is_deleted_idx. Partial rather than a plain btree over the column: every query
-- that uses it wants the trash (the listing, and the cleanup service's purge sweep), which is the
-- small side of the table, and a partial index does not carry an entry for the live majority.
CREATE INDEX IF NOT EXISTS inodes_deleted_at_idx
    ON inodes (deleted_at)
    WHERE deleted_at IS NOT NULL;

-- Existing tombstones keep their DELETED_ external id ON PURPOSE.
--
-- Their file on disk is still named after that string, and the new scheme names trash files by node
-- id. Un-mangling external_id here would leave those rows pointing at a filename that no longer
-- describes anything on disk, and the checksum and epoch needed to reconstruct it would be gone --
-- every file already in the trash would become unrestorable. FileSystemService.restoreOne therefore
-- keeps a legacy branch, and it can be deleted once the trash has been emptied of pre-migration
-- entries.
