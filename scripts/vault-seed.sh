#!/usr/bin/env bash
#
# Seed the local dev-mode Vault (from docker-compose.yml) with every secret the
# DataHub services read at startup. Run AFTER `docker compose up -d`.
#
#   ./scripts/vault-seed.sh
#
# It enables a KV-v2 engine at the mount the code expects (intellistream-datahub),
# writes the global secrets and a per-tenant connection registry, then enables
# AppRole auth and prints the role-id / secret-id you must paste into your
# application-local config (see GETTING_STARTED.md). Re-running is safe (it overwrites).
#
# Requires the `vault` CLI on your PATH, OR run it inside the container:
#   docker compose exec -T vault sh < scripts/vault-seed.sh
set -euo pipefail

export VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"
# Token resolution: explicit VAULT_TOKEN wins; otherwise read the root token saved by
# scripts/vault-init.sh (persistent Vault generates it on init). The old dev-mode
# "root-token" remains the last-resort default for backward compatibility.
VAULT_INIT_FILE="${VAULT_INIT_FILE:-./vault-init.json}"
if [ -z "${VAULT_TOKEN:-}" ] && [ -f "$VAULT_INIT_FILE" ]; then
  VAULT_TOKEN=$(tr -d '\n' < "$VAULT_INIT_FILE" | sed -n 's/.*"root_token": *"\([^"]*\)".*/\1/p')
fi
export VAULT_TOKEN="${VAULT_TOKEN:-root-token}"

# Vault must be unsealed before we can write. Fail fast with a clear hint if it isn't.
if ! vault status >/dev/null 2>&1; then
  echo "ERROR: Vault at $VAULT_ADDR is unreachable or SEALED." >&2
  echo "       Run ./scripts/vault-init.sh and unseal it first (see GETTING_STARTED.md)." >&2
  exit 1
fi

MOUNT="intellistream-datahub"

# Service hostnames as seen *from your host* when running the apps via ./gradlew
# bootRun. If you instead run the apps inside the compose network, swap these for
# the service names (postgres, clickhouse, ...).
PG_HOST="${PG_HOST:-localhost:5433}"   # compose publishes Postgres on host 5433 (5432 is a native PG)
CH_HOST="${CH_HOST:-localhost:8123}"
NEO_HOST="${NEO_HOST:-localhost:7687}"
KV_HOST="${KV_HOST:-localhost:6666}"
VALKEY_HOST="${VALKEY_HOST:-localhost}"
PULSAR_HOST="${PULSAR_HOST:-localhost}"
KEYCLOAK="${KEYCLOAK:-http://localhost:8090}"

# The two demo tenants. Each org-id must match the "organization" claim that
# deploy/keycloak/datahub-realm.json injects for the matching user (foo -> FOO_ID,
# bar -> BAR_ID). Change them here and in the realm together.
FOO_ID="${FOO_ID:-11111111-1111-1111-1111-111111111111}"
BAR_ID="${BAR_ID:-22222222-2222-2222-2222-222222222222}"

echo "==> Enabling KV-v2 at $MOUNT"
vault secrets enable -path="$MOUNT" kv-v2 2>/dev/null || echo "    (already enabled)"

echo "==> Writing shared platform secret (datahub-platform)"
# Post-(master-merge) layout. Read by the infra VaultConfigurationLoader
# (api + both consumers) and, for keycloak.issuer, by the api (VaultKeycloakData)
# and the console. These are FLAT dotted string keys — KV-v2 field names that
# literally contain dots, NOT nested objects. The pulsar OAuth/admin fields are
# unused with plaintext pulsar (DATAHUB_PULSAR_TLS_ENABLED=false) but must exist
# so the loader doesn't bind nulls.
vault kv put "$MOUNT/datahub-platform" \
  pulsar.host="$PULSAR_HOST" \
  pulsar.issuer-url="unused-local" \
  pulsar.client-id=local pulsar.client-secret=local \
  pulsar.admin-client-id=local pulsar.admin-client-secret=local \
  pulsar.internal-tenant=datahub-internal \
  keycloak.issuer="$KEYCLOAK/realms/datahub"

echo "==> Writing console secret (datahub-console)"
# Read ONLY by the console loader: the OAuth2 login client (oauth.*) plus the
# console's Spring Session Valkey store (http.session.valkey.*). The console
# reads its issuer from datahub-platform's keycloak.issuer, so it is NOT repeated
# here. (oauth.client-id/secret replace the old keycloak secret's
# datahub_client_id/secret.)
#
# oauth.scope MUST include an organization selector. Without one Keycloak emits no
# "organization" claim at all and datahub-api rejects every token the console relays, so
# login appears to work and then every API call 401s. "organization:*" means "whichever
# organization this user belongs to", which is what a console serving many tenants needs;
# a user belonging to several gets an ambiguous token that the api refuses rather than
# guessing at. The bare name "organization" is NOT usable — Keycloak 500s on it.
# See datahub-api/KEYCLOAK_ORG_GROUPS.md.
vault kv put "$MOUNT/datahub-console" \
  oauth.client-id=datahub-client \
  oauth.client-secret=changeme \
  oauth.client-name=datahub \
  oauth.provider=keycloak \
  oauth.scope='openid,organization:*' \
  oauth.grant-type=authorization_code \
  oauth.redirect-uri="{baseUrl}/login/oauth2/code/{registrationId}" \
  oauth.realm-roles-jsonpath='$.realm_access.roles' \
  oauth.client-roles-jsonpath='$.resource_access.*.roles' \
  console.datahub-url="${DATAHUB_URL:-http://localhost:8081}" \
  http.session.valkey.host="$VALKEY_HOST" \
  http.session.valkey.port=6379 \
  http.session.valkey.user=default \
  http.session.valkey.password=changeme \
  llm.api-key="${DATAHUB_CHAT_API_KEY:-changeme}" \
  llm.model="${DATAHUB_CHAT_MODEL:-claude-opus-5}"

# Per-tenant connection registry — the source of truth for PostgreSQL, ClickHouse,
# Neo4j, Valkey and KVRocks connections. Two demo tenants keyed by org-name (foo, bar);
# each org-id must match the "organization" claim in that tenant's JWT (see
# deploy/keycloak/datahub-realm.json). Field names map to the @JsonProperty annotations
# on the Tenant model (see datahub-commons .../tenant/). Store host fields are bare (the
# code appends each store's port); the postgresql "uri" is a full JDBC URL. The "user"
# must be set so the JDBC driver doesn't fall back to the OS username; the password is
# empty by design (Postgres runs with trust auth locally). foo and bar each route to
# their own Postgres + ClickHouse database (created by the deploy/*/init scripts); Neo4j
# is shared (community edition has a single database).
#
# The whole registry is written as one JSON object piped on stdin (`... databases -`)
# so each tenant value is a nested JSON OBJECT. Writing `foo={...}` instead would store
# the value as a STRING, which TenantConfigService cannot deserialize into a Tenant.
#
vault kv put "$MOUNT/tenant-resources" - <<JSON
{
  "foo": {
    "org-id": "$FOO_ID",
    "pulsar":     { "tenant": "foo" },
    "postgresql": { "uri": "jdbc:postgresql://$PG_HOST/foo", "user": "foobar" },
    "clickhouse": { "host": "${CH_HOST%%:*}", "user": "foobar", "password": "changeme", "database": "foo" },
    "neo4j":      { "host": "${NEO_HOST%%:*}", "user": "neo4j", "password": "changeme123", "database": "neo4j" },
    "valkey":     { "host": "$VALKEY_HOST", "port": 6379, "user": "default", "password": "changeme" },
    "kvrocks":    { "host": "${KV_HOST%%:*}", "port": 6666 },
    "file-storage": { "host": "localhost", "root-path": "/tmp/datahub/foo/files", "trash-path": "/tmp/datahub/foo/trash" },
    "tenant-config": { "files": true, "chat": true }
  },
  "bar": {
    "org-id": "$BAR_ID",
    "pulsar":     { "tenant": "bar" },
    "postgresql": { "uri": "jdbc:postgresql://$PG_HOST/bar", "user": "foobar" },
    "clickhouse": { "host": "${CH_HOST%%:*}", "user": "foobar", "password": "changeme", "database": "bar" },
    "neo4j":      { "host": "${NEO_HOST%%:*}", "user": "neo4j", "password": "changeme123", "database": "neo4j" },
    "valkey":     { "host": "$VALKEY_HOST", "port": 6379, "user": "default", "password": "changeme" },
    "kvrocks":    { "host": "${KV_HOST%%:*}", "port": 6666 },
    "file-storage": { "host": "localhost", "root-path": "/tmp/datahub/bar/files", "trash-path": "/tmp/datahub/bar/trash" },
    "tenant-config": { "files": true, "chat": true }
  }
}
JSON

# Per-tenant model configuration, one secret per tenant keyed by ORG ID (not org name: every
# request carries an id, and keying on it means the write path needs no name lookup and renaming
# an organization does not strand its configuration).
#
# This is the ONLY thing any application may write to Vault — see the policy below. It is separate
# from tenant-resources precisely so that the write capability cannot reach a database credential.
#
# Seeded for foo only. bar deliberately has none and falls back to the deployment-wide llm.*
# defaults on the datahub-console secret, so the default dev stack exercises both halves of the
# precedence rule without anyone having to edit Vault by hand first.
echo "==> Seeding per-tenant model config (foo only; bar uses the deployment default)"
vault kv put "$MOUNT/tenant-llm/$FOO_ID" \
  provider=anthropic \
  api-key="${DATAHUB_CHAT_API_KEY:-changeme}" \
  model="${DATAHUB_CHAT_MODEL:-claude-opus-5}"

echo "==> Enabling AppRole auth + datahub policy"
vault auth enable approle 2>/dev/null || echo "    (already enabled)"
# Read everything; write exactly one thing. tenant-llm/<org-id> is the only path any application
# may write, and it holds nothing but model settings — which is the entire reason it is a separate
# secret. Vault policies are path-based and KV plugins do not support allowed_parameters, so
# "may update tenant-resources.<tenant>.llm and nothing else" is not expressible at any mount;
# giving the model config its own path is the only shape in which Vault can enforce that boundary.
# Without the split, granting this write would also grant the ability to repoint any tenant's
# Postgres, since tenant-resources holds every tenant's database credentials.
vault policy write datahub - <<EOF
path "$MOUNT/*"      { capabilities = ["read", "list"] }
path "$MOUNT/data/*" { capabilities = ["read", "list"] }

path "$MOUNT/data/tenant-llm/*" { capabilities = ["read", "create", "update"] }
EOF
vault write auth/approle/role/datahub \
  token_policies=datahub token_ttl=1h token_max_ttl=4h secret_id_num_uses=0 secret_id_ttl=0

# By default the role-id is generated and a fresh secret-id is minted (host workflow:
# you paste them into application-local.yml). When VAULT_ROLE_ID / VAULT_SECRET_ID are
# set (the compose `apps` profile does this), pin them to those fixed values so the app
# containers can authenticate with credentials known ahead of time.
if [ -n "${VAULT_ROLE_ID:-}" ]; then
  vault write auth/approle/role/datahub/role-id role_id="$VAULT_ROLE_ID" >/dev/null
fi
ROLE_ID="$(vault read -field=role_id auth/approle/role/datahub/role-id)"
if [ -n "${VAULT_SECRET_ID:-}" ]; then
  # Idempotent: on a PERSISTENT Vault the fixed secret-id is already registered on
  # a re-run ("SecretID is already registered", HTTP 500). That's fine — the
  # existing secret-id still authenticates — so tolerate the failure instead of
  # aborting the whole seed (set -e would otherwise kill it here).
  vault write auth/approle/role/datahub/custom-secret-id secret_id="$VAULT_SECRET_ID" >/dev/null 2>&1 \
    || echo "    (custom secret-id already registered — reusing)"
  SECRET_ID="$VAULT_SECRET_ID"
else
  SECRET_ID="$(vault write -f -field=secret_id auth/approle/role/datahub/secret-id)"
fi

cat <<DONE

==> Done. Put these into datahub-*/src/main/resources/application-local.yml
    (gitignored) or export as env vars before running the services:

  vault.address:   http://localhost:8200
  vault.role-id:   $ROLE_ID
  vault.secret-id: $SECRET_ID

See GETTING_STARTED.md for the rest of the application-local.yml (Pulsar
plaintext overrides) and how to start the apps.
DONE
