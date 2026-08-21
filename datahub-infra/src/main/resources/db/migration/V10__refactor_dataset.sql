INSERT INTO node_type (name, description, date_created, last_updated) VALUES
    ('DATASET', 'Data Set Node Type', current_timestamp, current_timestamp),
    ('POLICY', 'POLICY Node Type', current_timestamp, current_timestamp);

DROP TABLE dataset_metadata;
ALTER TABLE edge DROP CONSTRAINT edge_data_set_id_fk;
ALTER TABLE inodes DROP CONSTRAINT inode_data_set_id_fk;
ALTER TABLE node DROP CONSTRAINT node_data_set_id_fk;
DROP TABLE data_set CASCADE;