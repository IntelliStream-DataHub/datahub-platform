#!/usr/bin/env bash
#
# Block until the stack can actually serve a tenant-scoped request, or fail naming
# the hop that is broken.
#
#   ./scripts/ci-wait-ready.sh
#
# Why this is not a health check: no service exposes one. Every module ships with
# `management.endpoints.web.exposure.include: ""` and
# `management.health.defaults.enabled: false`, deliberately — the tenant-routing
# datasource has no connection to probe without a tenant. So readiness has to be
# established from outside, by doing the thing a client does.
#
# Four stages, weakest signal to strongest. Each one exists because the stage above
# it can pass while the platform is still unusable:
#
#   1. Liveness. The apps run `restart: unless-stopped`, so a service that cannot
#      start restarts forever and every other probe below just times out. Checking
#      it each iteration turns a ten-minute hang into a twenty-second failure.
#   2. Context up. /api-docs is permitAll and only serves from a fully built
#      context, so a 200 already proves Vault, Pulsar, Keycloak and the per-tenant
#      Flyway migration all succeeded.
#   3. The `organization` claim resolves. keycloak-bootstrap applies protocol
#      mappers that a realm import cannot carry; until it has, the claim is a flat
#      array of aliases and EVERY tenant-scoped call 401s. This gate fires about
#      ten seconds after the OIDC-discovery gate the app containers wait on, which
#      is why they are not sufficient on their own.
#   4. A real tenant-scoped read. The first such call is what triggers
#      TenantProvisioningFilter and its per-tenant migration, so early non-200s are
#      expected and retried, not fatal. POST /datasets/filter, not /datasets/list —
#      the latter was removed as a duplicate name for the same handler.
#
# Stages 3 and 4 mint a FRESH token every attempt on purpose: a token obtained
# before keycloak-bootstrap finished carries the wrong claim shape and would keep
# failing forever if cached.
set -uo pipefail
cd "$(dirname "$0")/.."

API="${API:-http://localhost:8081}"
KC="${KC:-http://keycloak:8090}"
REALM="${REALM:-datahub}"
TENANT="${TENANT:-foo}"
CLIENT_ID="${CLIENT_ID:-datahub-service-${TENANT}}"
CLIENT_SECRET="${CLIENT_SECRET:-changeme-${TENANT}}"
TIMEOUT="${TIMEOUT:-600}"
COMPOSE_FILES="${COMPOSE_FILES:--f docker-compose.yml -f docker-compose.apps.yml}"
APPS="${APPS:-datahub-api datahub-stateless-consumer}"

TOKEN_URI="${TOKEN_URI:-${KC}/realms/${REALM}/protocol/openid-connect/token}"

CLI="${CONTAINER_CLI:-}"
if [ -z "$CLI" ]; then
  if command -v podman >/dev/null 2>&1; then CLI=podman; else CLI=docker; fi
fi
compose() { $CLI compose $COMPOSE_FILES "$@"; }

deadline=$(( $(date +%s) + TIMEOUT ))
say() { printf '%s\n' "$*"; }

diagnose() {
  say ""
  say "--- container state ---"
  compose ps -a 2>&1 || true
  for s in $APPS keycloak keycloak-bootstrap vault-seed; do
    say ""
    say "--- $s (last 60 lines) ---"
    compose logs --no-color --tail=60 "$s" 2>&1 || true
  done
}

fail() {
  say ""
  say "ci-wait-ready.sh: FAILED — $*"
  say "  API=$API  TOKEN_URI=$TOKEN_URI  CLIENT_ID=$CLIENT_ID"
  diagnose
  exit 1
}

# --- 1. liveness ---------------------------------------------------------------
# A crash-looping app never reaches "running" for long. Anything other than
# running or restarting means it is gone for good; restarting repeatedly is
# reported by the caller's timeout.
assert_alive() {
  local state
  for s in $APPS; do
    state="$(compose ps --format '{{.State}}' "$s" 2>/dev/null | head -1)"
    case "$state" in
      running|restarting|created|"") ;;
      *) fail "service '$s' is '$state' — it exited instead of starting" ;;
    esac
  done
}

# --- helpers -------------------------------------------------------------------
mint_token() {
  curl -s --max-time 15 "$TOKEN_URI" \
    -d grant_type=client_credentials \
    -d "client_id=${CLIENT_ID}" \
    -d "client_secret=${CLIENT_SECRET}" \
    --data-urlencode "scope=openid organization:*" \
  | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("access_token",""))
except Exception: print("")' 2>/dev/null
}

# Print "object" when the claim is the nested shape datahub-api requires, else a
# repr of whatever is actually there, so the failure message names the cause.
claim_shape() {
  printf '%s' "$1" | python3 -c '
import sys, json, base64
try:
    p = sys.stdin.read().split(".")[1]
    p += "=" * (-len(p) % 4)
    c = json.loads(base64.urlsafe_b64decode(p)).get("organization")
except Exception as e:
    print(f"undecodable ({e})"); raise SystemExit
print("object" if isinstance(c, dict) and c and all(
    isinstance(v, dict) and v.get("id") for v in c.values()) else repr(c))'
}

wait_for() {
  local label=$1 fn=$2 detail
  say "waiting for ${label} ..."
  while :; do
    assert_alive
    if detail="$($fn)"; then
      say "  ok    ${label}${detail:+ — $detail}"
      return 0
    fi
    [ "$(date +%s)" -lt "$deadline" ] || fail "timed out after ${TIMEOUT}s waiting for ${label} (last: ${detail:-none})"
    sleep 3
  done
}

# --- 2. the api serves an unauthenticated endpoint ------------------------------
check_api_docs() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$API/api-docs" 2>/dev/null)"
  [ "$code" = "200" ] || { printf 'HTTP %s' "$code"; return 1; }
}

# --- 3. the organization claim resolves -----------------------------------------
check_org_claim() {
  local tok shape
  tok="$(mint_token)"
  [ -n "$tok" ] || { printf 'no token from %s' "$TOKEN_URI"; return 1; }
  shape="$(claim_shape "$tok")"
  [ "$shape" = "object" ] || { printf 'claim is %s' "$shape"; return 1; }
}

# --- 4. a tenant-scoped read succeeds -------------------------------------------
check_tenant_read() {
  local tok code
  tok="$(mint_token)"
  [ -n "$tok" ] || { printf 'no token'; return 1; }
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST "$API/datasets/filter" \
            -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d '{}' 2>/dev/null)"
  [ "$code" = "200" ] || { printf 'datasets/filter -> %s' "$code"; return 1; }
}

say "ci-wait-ready.sh: API=$API  issuer host=${KC}  tenant=${TENANT}  timeout=${TIMEOUT}s"
wait_for "datahub-api to serve /api-docs"          check_api_docs
wait_for "the organization claim to resolve"       check_org_claim
wait_for "a tenant-scoped read (datasets/filter)"  check_tenant_read
say ""
say "stack is ready"
