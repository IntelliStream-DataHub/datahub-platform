
ALTER TABLE node
    DROP CONSTRAINT node_asset_id_fk;

ALTER TABLE node
    DROP CONSTRAINT node_function_id_fk;

ALTER TABLE node
    DROP CONSTRAINT node_timeseries_id_fk;

ALTER TABLE timeseries_security_categories
    DROP CONSTRAINT timeseries_security_categories_metadata_id_fk;

ALTER TABLE timeseries
    DROP CONSTRAINT timeseries_value_type_id_fk;

ALTER TABLE edge_metadata
    DROP COLUMN id;

ALTER TABLE edge_metadata
    ADD CONSTRAINT pk_edge_metadata PRIMARY KEY (edge_id, key);

ALTER TABLE node
    ADD node_type BIGINT;

ALTER TABLE node
    ADD source VARCHAR(128);

ALTER TABLE node
    ADD table_engine SMALLINT;

ALTER TABLE node
    ADD unit VARCHAR(255);

ALTER TABLE node
    ADD unit_external_id VARCHAR(255);

ALTER TABLE node
    ADD value_type_id INTEGER;

UPDATE node
SET node_type = 0
WHERE node_type is null;


ALTER TABLE node
    ADD CONSTRAINT node_dataset_id_fk FOREIGN KEY (data_set_id) REFERENCES node (id);


ALTER TABLE node_metadata
    DROP CONSTRAINT node_data_node_id_fk;

Alter Table node_metadata
    ADD CONSTRAINT node_data_node_id_fk FOREIGN KEY (node_id) REFERENCES node (id) ON DELETE CASCADE;

ALTER TABLE node
    ADD CONSTRAINT timeseries_value_type_id_fk FOREIGN KEY (value_type_id) REFERENCES timeseries_value_type (id);

ALTER TABLE timeseries_security_categories
    ADD CONSTRAINT timeseries_security_categories_timeseries_id_fk FOREIGN KEY (timeseries_id) REFERENCES node (id);

DROP TABLE asset CASCADE;

DROP TABLE event CASCADE;

DROP TABLE event_metadata CASCADE;

DROP TABLE event_node_ids CASCADE;

DROP TABLE function_entity CASCADE;

DROP TABLE timeseries CASCADE;

ALTER TABLE node_metadata
    DROP COLUMN id;

ALTER TABLE node
    DROP COLUMN asset_id;

ALTER TABLE node
    DROP COLUMN function_id;

ALTER TABLE node
    DROP COLUMN node_type_id;

ALTER TABLE node
    DROP COLUMN timeseries_id;


ALTER TABLE node
    ALTER COLUMN is_root DROP NOT NULL;

ALTER TABLE node_metadata
    ADD CONSTRAINT pk_node_metadata PRIMARY KEY (node_id, key);