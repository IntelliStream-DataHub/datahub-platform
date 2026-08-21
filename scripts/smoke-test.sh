#!/usr/bin/env bash
#
# Assert that a running stack actually works end to end, from outside it.
#
#   ./scripts/smoke-test.sh
#
# Written for the failure mode this stack actually has: things that report success while
# losing data. Both message-loss bugs it checks for were live in the compose stack and
# invisible to every other signal — containers healthy, api answering 2xx, seed exiting 0,
# and a Neo4j graph missing two thirds of its nodes.
#
# Exit 0 = the platform ingested what it was given. Non-zero names the broken hop.
set -uo pipefail
cd "$(dirname "$0")/.."

API="${API:-http://localhost:8081}"

# Which Keycloak host to mint tokens from. It has to be the one vault-seed wrote as the
# issuer, because the api validates `iss` against exactly that — mint anywhere else and
# every call 401s with correct credentials. Rather than assume, read it back from the
# vault-seed container that actually seeded this stack; `up.sh` sets it to the host IP,
# a bare `compose up` leaves it as the compose DNS name.
detect_kc_addr() {
  podman inspect "${COMPOSE_PROJECT:-datahub}-vault-seed-1" \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
  | sed -n 's#^KEYCLOAK=http://\([^:]*\):.*#\1#p' | head -1
}
KC_ADDR="${KC_ADDR:-$(detect_kc_addr)}"
KC_ADDR="${KC_ADDR:-keycloak}"
TENANT="${TENANT:-foo}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-datahub}"

fails=0

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ok    %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*"; fails=$((fails + 1)); }

ctr() { podman ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$1"; }
pexec() { podman exec "$1" "${@:2}"; }

# --- 1. Pulsar delivered everything it was given -------------------------------------------
#
# A consumer that subscribes AFTER a message is published never receives it: a new
# subscription defaults to Latest, and the message is dropped with no backlog and no error.
# published != delivered is the only place this is visible.
say "pulsar delivery (published == delivered)"
check_topic() {
  local topic=$1 cmd=${2:-stats}
  local out
  out=$(pexec "${COMPOSE_PROJECT}-pulsar-1" bin/pulsar-admin topics "$cmd" "$topic" 2>/dev/null) || {
    bad "$topic: could not read stats"; return; }
  printf '%s' "$out" | python3 -c '
import sys, json
topic = sys.argv[1]
try:
    d = json.load(sys.stdin)
except ValueError:
    print(f"  FAIL  {topic}: unreadable stats"); sys.exit(1)
published = d.get("msgInCounter") or 0
subs = d.get("subscriptions") or {}
if not subs:
    print(f"  FAIL  {topic}: no subscription (consumer never attached)"); sys.exit(1)
rc = 0
for name, s in subs.items():
    delivered = s.get("msgOutCounter") or 0
    backlog = s.get("msgBacklog") or 0
    if delivered + backlog < published:
        print(f"  FAIL  {topic} [{name}]: {published} published, {delivered} delivered, "
              f"{backlog} backlog — {published - delivered - backlog} lost")
        rc = 1
    else:
        print(f"  ok    {topic} [{name}]: {delivered}/{published} delivered")
sys.exit(rc)' "$topic" || fails=$((fails + 1))
}
check_topic persistent://datahub-internal/resources/cud-events
check_topic persistent://datahub-internal/datapoints/all-datapoints partitioned-stats

# --- 2. The graph consumer actually applied to Neo4j ---------------------------------------
say "neo4j graph"
nodes=$(pexec "${COMPOSE_PROJECT}-neo4j-1" cypher-shell -u neo4j -p changeme123 --format plain \
        "MATCH (n) RETURN count(n);" 2>/dev/null | tail -1 | tr -d '\r')
if [ "${nodes:-0}" -gt 0 ] 2>/dev/null; then ok "$nodes nodes"; else bad "no nodes in neo4j"; fi

# --- 3. The api serves a tenant-scoped read ------------------------------------------------
say "api"
tok=$(curl -s --max-time 10 "http://${KC_ADDR}:8090/realms/datahub/protocol/openid-connect/token" \
        -d grant_type=client_credentials -d "client_id=datahub-service-${TENANT}" \
        -d "client_secret=changeme-${TENANT}" --data-urlencode "scope=openid organization:${TENANT}" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null)
if [ -z "$tok" ]; then
  bad "could not mint a token at ${KC_ADDR}:8090 (KC_ADDR=$KC_ADDR — must match the issuer vault-seed wrote)"
else
  # The claim the api resolves a tenant from. A flat array means keycloak-bootstrap has not
  # run, and every tenant-scoped call will 401.
  shape=$(printf '%s' "$tok" | python3 -c '
import sys, json, base64
p = sys.stdin.read().split(".")[1]; p += "=" * (-len(p) % 4)
c = json.loads(base64.urlsafe_b64decode(p)).get("organization")
print("object" if isinstance(c, dict) and c and all(
    isinstance(v, dict) and v.get("id") for v in c.values()) else repr(c))')
  [ "$shape" = "object" ] && ok "organization claim resolvable" \
                          || bad "organization claim is $shape — keycloak-bootstrap has not applied its mappers"
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$API/datasets/list" \
           -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d '{}')
  [ "$code" = "200" ] && ok "datasets/list -> 200" || bad "datasets/list -> $code"
fi

# --- 4. The demo landed, if it is part of this stack ----------------------------------------
if ctr "${COMPOSE_PROJECT}-demo-seed-1"; then
  say "demo"
  state=$(podman inspect "${COMPOSE_PROJECT}-demo-seed-1" --format '{{.State.Status}}:{{.State.ExitCode}}')
  [ "$state" = "exited:0" ] && ok "demo-seed $state" || bad "demo-seed $state"
  if [ -n "${tok:-}" ]; then
    n=$(curl -s --max-time 20 -X POST "$API/timeseries/data/list" \
          -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
          -d '{"items":[{"externalId":"synthetic_3w_well_00001_pdg_pressure","start":"2020-01-01T00:00:00Z","end":"2035-01-01T00:00:00Z","limit":5000}]}' \
        | python3 -c 'import sys,json
try: print(len(json.load(sys.stdin)["items"][0]["datapoints"]))
except Exception: print(0)')
    # Seeded history is thousands of points; a handful means the backfill was published but
    # never stored, and only the live feeder's writes survived.
    [ "${n:-0}" -gt 1000 ] && ok "$n datapoints readable for well 1" \
                           || bad "only ${n:-0} datapoints for well 1 — history did not reach clickhouse"
  fi
fi

say ""
[ "$fails" -eq 0 ] && { say "smoke test passed"; exit 0; }
say "smoke test FAILED ($fails check(s))"; exit 1
