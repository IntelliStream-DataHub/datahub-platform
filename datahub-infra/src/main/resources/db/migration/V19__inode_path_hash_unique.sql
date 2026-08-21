-- Make the DB authoritative for "this path is taken". Before this, two stateless
-- datahub-api instances (or two concurrent requests on one instance) could both
-- pass a Files.exists() / SELECT check and race to write the same path on disk.
-- With a unique index on path_hash for active rows, the second INSERT fails
-- deterministically with a constraint violation, regardless of filesystem timing
-- or NFS attribute caching.
--
-- Partial (WHERE is_deleted = false) so a soft-deleted path can be re-used by
-- a new upload without hitting the constraint.
--
-- If this migration fails, the most likely cause is pre-existing duplicates
-- produced by the old silent-duplication race on DirectoryService folder
-- creation. Dedupe (soft-delete the extras) before re-running.
CREATE UNIQUE INDEX inodes_path_hash_active_uk
    ON inodes (path_hash)
    WHERE is_deleted = false;
