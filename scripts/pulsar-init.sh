#!/bin/sh
# Bootstrap the Pulsar tenants + namespaces the DataHub apps expect (LOCAL DEV).
#
# The standalone broker starts empty (only public/default), but the apps open
# producers/consumers on datahub-internal/* and datahub-public/* at startup. The
# API's own InitNamespaces only covers a subset and runs after its eager producer
# beans, so on a fresh broker we provision everything up front here. Topics
# auto-create within these namespaces (standalone has allowAutoTopicCreation=true).
#
# Namespace set mirrors TopicNames / datahub-api/PULSAR_SETUP.md.
set -eu

ADMIN_URL="${PULSAR_ADMIN_URL:-http://pulsar:8080}"
INTERNAL="${INTERNAL_TENANT:-datahub-internal}"
PUBLIC="${PUBLIC_TENANT:-datahub-public}"
CLUSTER="${PULSAR_CLUSTER:-standalone}"
PA="bin/pulsar-admin --admin-url ${ADMIN_URL}"

echo "[pulsar-init] waiting for Pulsar admin at ${ADMIN_URL} ..."
until $PA clusters list >/dev/null 2>&1; do sleep 2; done

create_tenant() {
  $PA tenants create "$1" --allowed-clusters "$CLUSTER" 2>/dev/null \
    && echo "[pulsar-init] created tenant $1" \
    || echo "[pulsar-init] tenant $1 already exists"
}

create_ns() {
  $PA namespaces create "$1" 2>/dev/null \
    && echo "[pulsar-init] created namespace $1" \
    || echo "[pulsar-init] namespace $1 already exists"
}

create_tenant "$INTERNAL"
create_tenant "$PUBLIC"

# Per-customer Pulsar tenants for the demo tenants (foo, bar). In production a
# control plane provisions these; the dev stack creates them here so the API's
# SubscriptionTopicProvisioner can create each customer's fan-out namespace+topic
# (it assumes the customer's Pulsar tenant already exists). Keep in sync with the
# tenant `pulsar.tenant` values in scripts/vault-seed.sh.
for ct in ${CUSTOMER_TENANTS:-foo bar}; do
  create_tenant "$ct"
done

for ns in datapoints events subscriptions http; do
  create_ns "${INTERNAL}/${ns}"
done
create_ns "${PUBLIC}/subscriptions"

echo "[pulsar-init] done. ${INTERNAL} namespaces:"
$PA namespaces list "$INTERNAL"
echo "[pulsar-init] done. ${PUBLIC} namespaces:"
$PA namespaces list "$PUBLIC"
