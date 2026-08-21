-- The Pulsar user-managed streams feature (namespaces / topics / roles) is gone: the
-- StreamController, PulsarService and their JPA entities have been removed. These six
-- tables backed only that feature. The Pulsar messaging backbone (datapoints / resources
-- / events topics and their consumers) provisions its own topics at runtime and never used
-- these tables, so dropping them is safe. IF EXISTS keeps this idempotent on environments
-- where a partial migration left a table absent; CASCADE clears the inter-table FKs
-- (permissions -> namespace/topic/role, subscription -> topic) without an explicit order.
DROP TABLE IF EXISTS stream_subscription CASCADE;
DROP TABLE IF EXISTS stream_topic_permission CASCADE;
DROP TABLE IF EXISTS stream_namespace_permission CASCADE;
DROP TABLE IF EXISTS stream_topic CASCADE;
DROP TABLE IF EXISTS stream_role CASCADE;
DROP TABLE IF EXISTS stream_namespace CASCADE;
