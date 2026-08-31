-- TimeseriesEntity.securityCategories is gone. The field was stored, writable and returned,
-- but nothing ever read it: it took no part in access control (dataset ACLs are Keycloak
-- organization groups), no client consumed it, and no query filtered on it. This table backed
-- only that field, so nothing else references it.
-- IF EXISTS keeps the drop idempotent on environments where a partial migration left the
-- table absent; CASCADE clears the FK to `node` (added in V14) without an explicit order.
-- Files keep their own inodes_security_categories — a separate field on a separate entity.
DROP TABLE IF EXISTS timeseries_security_categories CASCADE;
