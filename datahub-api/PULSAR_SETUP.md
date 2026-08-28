```
bin/pulsar-admin tenants create datahub
bin/pulsar-admin tenants create datahub-public
bin/pulsar-admin namespaces create ${TENANT}/datapoints
bin/pulsar-admin namespaces grant-permission ${TENANT}/datapoints --actions produce,consume --role istream
bin/pulsar-admin namespaces set-backlog-quota ${TENANT}/datapoints -l 10G -p producer_exception
bin/pulsar-admin namespaces set-retention ${TENANT}/datapoints -s 11G -t 3d
bin/pulsar-admin namespaces create ${TENANT}/events
bin/pulsar-admin namespaces grant-permission ${TENANT}/events --actions produce,consume --role istream
bin/pulsar-admin namespaces set-backlog-quota ${TENANT}/events -l 10G -p producer_exception
bin/pulsar-admin namespaces set-retention ${TENANT}/events -s 11G -t 3d

bin/pulsar-admin namespaces create ${TENANT}/subscriptions
bin/pulsar-admin namespaces grant-permission ${TENANT}/subscriptions --actions produce,consume --role istream
bin/pulsar-admin namespaces set-backlog-quota ${TENANT}/subscriptions -l 10G -p producer_exception
bin/pulsar-admin namespaces set-retention ${TENANT}/subscriptions -s 11G -t 3d

# Per-customer subscription fan-out now lives in EACH customer's OWN Pulsar tenant
# (persistent://<customer-tenant>/subscriptions/fanout), resolved from the tenant's
# `pulsar.tenant` in the tenant-resources Vault registry. datahub-api's
# SubscriptionTopicProvisioner creates that `subscriptions` namespace and the partitioned
# fan-out topic automatically (in all profiles, and on tenant-add) — you only need the
# customer's Pulsar TENANT to exist. The notify topic stays on ${TENANT}/subscriptions
# (internal tenant) above. A tenant with no `pulsar.tenant` is refused (subscriptions fail
# loudly); there is no shared fallback, so every subscription-using tenant needs a pulsar block.
bin/pulsar-admin tenants create <customer-tenant>   # at customer onboarding, if not already present

bin/pulsar-admin namespaces create ${TENANT}/http
bin/pulsar-admin namespaces grant-permission ${TENANT}/http --actions produce,consume --role istream
bin/pulsar-admin namespaces set-backlog-quota ${TENANT}/http --type destination_storage -l 10G -p consumer_backlog_eviction
bin/pulsar-admin namespaces set-backlog-quota ${TENANT}/http --type message_age -lt 2w -p consumer_backlog_eviction
bin/pulsar-admin namespaces set-retention ${TENANT}/http -s 10G -t 5d
```