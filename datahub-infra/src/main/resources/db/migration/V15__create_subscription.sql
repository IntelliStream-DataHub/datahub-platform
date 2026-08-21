CREATE TABLE subscription (
    id                BIGSERIAL PRIMARY KEY,
    external_id       VARCHAR(256)             NOT NULL,
    external_id_hash  BIGINT                   NOT NULL,
    name              VARCHAR(256)             NOT NULL,
    timeseries_id     BIGINT                   NOT NULL,
    date_created      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_updated      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_subscription_external_id_hash UNIQUE (external_id_hash),
    CONSTRAINT fk_subscription_timeseries FOREIGN KEY (timeseries_id) REFERENCES node (id) ON DELETE CASCADE
);

CREATE INDEX idx_subscription_timeseries_id ON subscription (timeseries_id);
