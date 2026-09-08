-- Per-tenant overrides for the rate limits and ingest quotas.
--
-- Postgres rather than Vault: these are operational policy, not secrets, and they have to be
-- changeable while the platform runs. Vault's tenant config is read at boot and on TenantAddedEvent,
-- so raising a limit there would mean a restart; here it is an UPDATE, picked up by every instance
-- within the resolver's cache TTL. That matters because the whole point of a limit a caller can hit
-- is that someone can ask for it to be lifted.
--
-- One row per tenant schema, id fixed at 1 so there is nothing to choose between. Every limit column
-- is NULLABLE and means "inherit the deployment default from datahub.limits.*"; 0 or negative means
-- unlimited. A tenant with no row at all inherits everything, which is what makes this table
-- optional for an existing deployment.
CREATE TABLE IF NOT EXISTS tenant_limits (
    id                          smallint    PRIMARY KEY DEFAULT 1,

    -- Requests per minute. Tenant is the primary budget (one organization per signup); the per-user
    -- columns are the backstop that stops one identity inside a tenant spending all of it.
    write_per_minute_per_tenant integer     NULL,
    read_per_minute_per_tenant  integer     NULL,
    write_per_minute_per_user   integer     NULL,
    read_per_minute_per_user    integer     NULL,

    -- Rolling daily ingest budget, reset at 00:00 UTC.
    events_per_day              bigint      NULL,
    nodes_per_day               bigint      NULL,
    edges_per_day               bigint      NULL,
    datapoints_per_day          bigint      NULL,
    -- Bytes of write-request body per day. The one quota that actually bounds storage growth:
    -- entity counts do not, because a single entity may legitimately be a few hundred KB.
    ingest_bytes_per_day        bigint      NULL,

    -- Lifetime ceilings, the size of the sandbox rather than a rate. Nodes are counted live, so
    -- deleting frees room; the others accumulate, so create-and-delete churn cannot reset them.
    -- max_resources bounds the whole node table: resources, time series, data sets, labels,
    -- policies and functions are all rows in it, so one ceiling covers every kind.
    max_resources               bigint      NULL,
    max_events_total            bigint      NULL,
    max_datapoints_total        bigint      NULL,
    max_text_datapoints_total   bigint      NULL,

    -- Concurrent WebSocket connections. Durable Pulsar subscriptions cost the broker even when idle,
    -- so an idle hoard is as expensive as a busy one.
    max_ws_sockets_per_tenant   integer     NULL,
    max_ws_sockets_per_user     integer     NULL,

    -- Free-text note for whoever lifted a limit: who asked, and why it was granted.
    note                        text        NULL,
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT tenant_limits_single_row CHECK (id = 1)
);

-- Seed the inherit-everything row so an operator can UPDATE rather than having to know whether to
-- INSERT first.
INSERT INTO tenant_limits (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
