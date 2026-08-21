CREATE TABLE subscription_timeseries (
    subscription_id BIGINT NOT NULL,
    timeseries_id   BIGINT NOT NULL,
    CONSTRAINT pk_subscription_timeseries PRIMARY KEY (subscription_id, timeseries_id),
    CONSTRAINT fk_subtseries_subscription FOREIGN KEY (subscription_id) REFERENCES subscription (id) ON DELETE CASCADE,
    CONSTRAINT fk_subtseries_timeseries   FOREIGN KEY (timeseries_id)   REFERENCES node (id)         ON DELETE CASCADE
);

CREATE INDEX idx_subscription_timeseries_ts ON subscription_timeseries (timeseries_id);

-- Backfill: preserve existing single-timeseries bindings
INSERT INTO subscription_timeseries (subscription_id, timeseries_id)
SELECT id, timeseries_id
FROM subscription
WHERE timeseries_id IS NOT NULL;

-- Drop the old single-FK column and its index
ALTER TABLE subscription DROP CONSTRAINT IF EXISTS fk_subscription_timeseries;
DROP INDEX IF EXISTS idx_subscription_timeseries_id;
ALTER TABLE subscription DROP COLUMN timeseries_id;
