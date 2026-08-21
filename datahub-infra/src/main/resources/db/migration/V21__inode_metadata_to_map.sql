-- Convert inode_metadata from an entity collection (surrogate id) to an
-- @ElementCollection Map<String,String>, matching node_metadata / edge_metadata.

ALTER TABLE inode_metadata
    DROP COLUMN id;

ALTER TABLE inode_metadata
    ADD CONSTRAINT pk_inode_metadata PRIMARY KEY (inode_id, key);

ALTER TABLE inode_metadata
    DROP CONSTRAINT inode_metadata_inode_id_fk;

ALTER TABLE inode_metadata
    ADD CONSTRAINT inode_metadata_inode_id_fk
        FOREIGN KEY (inode_id) REFERENCES inodes (id) ON DELETE CASCADE;
