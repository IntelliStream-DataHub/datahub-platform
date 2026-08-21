#!/usr/bin/env bash
# Gate app startup on its dependencies being ready. compose `depends_on` conditions
# are unreliable under podman-compose, so we wait here instead:
#   1. Vault has been seeded (the per-tenant `databases` secret exists), which also
#      means the one-shot vault-seed service has finished.
#   2. Postgres accepts TCP connections (every app uses spring-data-jpa at startup,
#      and the API runs per-tenant Flyway migrations against it).
set -euo pipefail

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
VAULT_ROLE_ID="${VAULT_ROLE_ID:-}"
VAULT_SECRET_ID="${VAULT_SECRET_ID:-}"
SECRET_NAME="${VAULT_SECRET_NAME:-intellistream-datahub}"
PG_WAIT_HOST="${PG_WAIT_HOST:-postgres}"
PG_WAIT_PORT="${PG_WAIT_PORT:-5432}"
PULSAR_ADMIN_URL="${PULSAR_ADMIN_URL:-http://pulsar:8080}"
# Namespace pulsar-init creates last-ish; its presence means the Pulsar tenants and
# namespaces the apps' eager producers need are provisioned.
PULSAR_WAIT_NS="${PULSAR_WAIT_NS:-datahub-internal/http}"

# Gate on Vault being UNSEALED, the app's AppRole working, AND the per-tenant registry
# seeded — by doing the exact AppRole login the app itself will do (no root token needed;
# Vault here is persistent, so a static root token wouldn't be valid anyway). This also
# means we wait through manual unseal + the vault-seed one-shot in one check.
echo "[entrypoint] waiting for Vault (AppRole login + ${SECRET_NAME}/tenant-resources) at ${VAULT_ADDR} ..."
until token=$(curl -sf --data "{\"role_id\":\"${VAULT_ROLE_ID}\",\"secret_id\":\"${VAULT_SECRET_ID}\"}" \
          "${VAULT_ADDR}/v1/auth/approle/login" 2>/dev/null \
          | sed -n 's/.*"client_token":"\([^"]*\)".*/\1/p') \
        && [ -n "$token" ] \
        && curl -sf -H "X-Vault-Token: ${token}" \
          "${VAULT_ADDR}/v1/${SECRET_NAME}/data/tenant-resources" >/dev/null 2>&1; do
  sleep 2
done

echo "[entrypoint] waiting for Postgres at ${PG_WAIT_HOST}:${PG_WAIT_PORT} ..."
until (echo > "/dev/tcp/${PG_WAIT_HOST}/${PG_WAIT_PORT}") >/dev/null 2>&1; do
  sleep 2
done

# The API eagerly opens Pulsar producers during bean creation, so the namespaces
# must exist first. Wait until pulsar-init has created them.
pulsar_tenant="${PULSAR_WAIT_NS%%/*}"
echo "[entrypoint] waiting for Pulsar namespace ${PULSAR_WAIT_NS} ..."
until curl -sf "${PULSAR_ADMIN_URL}/admin/v2/namespaces/${pulsar_tenant}" 2>/dev/null \
        | grep -q "\"${PULSAR_WAIT_NS}\""; do
  sleep 2
done

# The API (OAuth2 resource server) and console (OAuth2 client) resolve the Keycloak issuer
# during bean creation via `fromIssuerLocation`, which fetches the realm's OIDC discovery
# document. Keycloak's port opens well before start-dev + realm import finish serving, so an
# early fetch hits a half-open socket ("Unexpected end of file from server") and Spring fails
# bean creation — fatal, since the app then exits. Gate on the realm actually serving its
# well-known doc. Set WAIT_FOR_OIDC_WELLKNOWN on the api and console services.
if [ -n "${WAIT_FOR_OIDC_WELLKNOWN:-}" ]; then
  echo "[entrypoint] waiting for Keycloak OIDC discovery at ${WAIT_FOR_OIDC_WELLKNOWN} ..."
  until curl -sf "${WAIT_FOR_OIDC_WELLKNOWN}" 2>/dev/null | grep -q '"issuer"'; do
    sleep 2
  done
fi

# The consumers depend on the API having created each tenant's schema: the API owns the
# per-tenant Flyway migration and only serves HTTP once it finishes. Without this gate a
# consumer can query a not-yet-migrated tenant DB at startup (e.g. SubscriptionCache),
# logging "relation ... does not exist". Set WAIT_FOR_API_URL on the consumer services.
if [ -n "${WAIT_FOR_API_URL:-}" ]; then
  echo "[entrypoint] waiting for API (per-tenant schema migrations) at ${WAIT_FOR_API_URL} ..."
  until curl -s -o /dev/null "${WAIT_FOR_API_URL}" >/dev/null 2>&1; do
    sleep 2
  done
fi

echo "[entrypoint] dependencies ready; starting: $*"
exec "$@"
