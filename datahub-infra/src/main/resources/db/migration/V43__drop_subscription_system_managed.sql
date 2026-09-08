-- Drop `subscription.system_managed`.
--
-- V20 added it for a function-binding lifecycle that was never built. Nothing has ever written
-- true to it: SubscriptionTransformer.toEntity does not set it, no other code path constructs a
-- SubscriptionEntity, and the /subscriptions/create endpoint does not expose the field — so the
-- column has held its DEFAULT false on every row of every tenant since it was added. The three
-- behaviours that read it (hidden from the filter endpoint, refuses a manual delete, skipped by
-- the cleanup sweep) were guards over an empty set.
--
-- The FunctionBindingLifecycleHandler that V20's comment names as the writer does not exist in
-- this repository. If that feature arrives, it adds the column back with the code that fills it,
-- which is cheaper than carrying a flag three call sites have to keep honouring in the meantime.
--
-- Safe to drop unconditionally: no index, constraint or view references it, and because every row
-- carries the default there is no data to preserve. `subscription_type` is deliberately left in
-- place — it is a Pulsar consumer setting the WebSocket handler still reads, even though only the
-- system-managed path would ever have set it to anything but FAILOVER.
ALTER TABLE subscription
    DROP COLUMN system_managed;
