#!/usr/bin/env bash
#
# One-command bring-up of the full local stack (backing services + the four apps) that
# removes the manual /etc/hosts step for browser login.
#
#   ./scripts/up.sh            # start everything, WITH the 3W demo seeded and streaming
#   ./scripts/up.sh --build    # (re)build the app images (jars built inside the image; no host JDK)
#   ./scripts/up.sh --no-demo  # bring up an empty stack instead
#
# The demo is on by default so that one command gives you a DataHub you can actually look
# at: an empty install shows empty pages, which is indistinguishable from a broken one. It
# costs seconds (the payloads under deploy/demo/data are pre-generated, nothing is
# downloaded) and lives entirely in the `foo` tenant.
#
# Note that compose does not stop services a later invocation leaves out, so after a normal
# run a later `--no-demo` leaves demo-feed running. Stop it with:
#   podman compose -f docker-compose.yml -f docker-compose.apps.yml \
#                  -f docker-compose.demo.yml stop demo-feed demo-seed
#
# Why this exists: browser login redirects to Keycloak, and the browser (on the host) and
# the in-network apps must agree on ONE Keycloak URL. The plain compose uses the compose
# DNS name `keycloak`, which the host browser can't resolve without `127.0.0.1 keycloak`
# in /etc/hosts. Instead we set the issuer host to THIS machine's IP, which the browser
# (it is the host) and the containers (via the published port) both reach — no hosts edit,
# no sudo. Override with `KC_ADDR=<ip-or-host> ./scripts/up.sh` if auto-detection is wrong.
#
# Vault is persistent + sealed: after this, initialise/unseal it and the apps will finish
# starting (see GETTING_STARTED.md):
#   ./scripts/vault-init.sh ; vault operator unseal <key>
set -euo pipefail
cd "$(dirname "$0")/.."

# Detect a routable host IP = the source address of the default route (Linux + macOS).
detect_host_ip() {
  if command -v ip >/dev/null 2>&1; then                       # Linux
    ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \([0-9.]*\).*/\1/p' | head -1
  elif command -v route >/dev/null 2>&1; then                  # macOS / BSD
    _if=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
    [ -n "$_if" ] && ipconfig getifaddr "$_if" 2>/dev/null
  fi
}
KC_ADDR="${KC_ADDR:-$(detect_host_ip)}"
if [ -z "$KC_ADDR" ]; then
  echo "up.sh: could not auto-detect a host IP." >&2
  echo "  Re-run as 'KC_ADDR=<reachable-ip> ./scripts/up.sh', or add '127.0.0.1 keycloak'" >&2
  echo "  to /etc/hosts and use the plain compose command instead." >&2
  exit 1
fi

# Prefer docker compose if present and working, else podman compose.
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
else
  COMPOSE=(podman compose)
fi

echo "up.sh: Keycloak issuer host = ${KC_ADDR}:8090 (no /etc/hosts needed)"
echo "up.sh: using '${COMPOSE[*]}'"
export KC_ADDR
# The app images build their boot jars inside a JDK 25 build stage
# (deploy/app/Dockerfile), so `--build` needs no host JDK / Gradle — podman only.
# WHAT the default stack contains is defined in exactly one place —
# docker-compose.override.yml, which compose auto-loads when no -f is given. So the default
# path here passes NO -f at all, rather than repeating that list: naming the files here too
# would mean a future overlay added to the override silently never reaches anyone using this
# script. Only the opt-out names files explicitly, precisely because it needs to exclude one.
#
# --no-demo/--demo are ours, not compose's, so they are stripped here; everything else is
# forwarded verbatim.
COMPOSE_FILES=()
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --no-demo) COMPOSE_FILES=(-f docker-compose.yml -f docker-compose.apps.yml)
               echo "up.sh: starting without the 3W demo" ;;
    --demo)    ;;   # the default; accepted so it is not passed on to compose
    *)         ARGS+=("$arg") ;;
  esac
done
[ ${#COMPOSE_FILES[@]} -eq 0 ] \
  && echo "up.sh: including the 3W demo (seed + live feed); --no-demo for an empty stack"

# ${ARGS[@]+...} rather than "${ARGS[@]}": under `set -u`, expanding an empty array is an
# unbound-variable error on bash 3.2, which is what macOS ships.
# Did this stack last get seeded with a DIFFERENT Keycloak issuer? vault-seed re-runs on
# every `up` and rewrites keycloak.issuer, but a running datahub-api only reads it once, at
# boot. So switching launcher — `./scripts/up.sh` uses this host's IP, a bare `compose up`
# uses the compose DNS name — leaves the stack working right up until the api next restarts,
# and then every token is rejected for an issuer mismatch that nothing recent explains.
# Recreate the apps so they pick up the new issuer immediately instead of arming that.
# Look the previous seed container up by its compose label, not a hardcoded name: docker names
# it `datahub-vault-seed-1` but podman-compose names it `datahub_vault-seed_1`, and a `podman
# inspect` on the wrong name exits 125 — which under `set -euo pipefail` aborts this whole
# script before it ever calls compose (the original cause of up.sh exiting 125 on podman). The
# trailing `|| true` keeps a missing container (or a docker-only host with no `podman`) benign.
PREV_KC=""
_seed_cid=$(podman ps -aq \
              --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-datahub}" \
              --filter "label=com.docker.compose.service=vault-seed" 2>/dev/null | head -1) || true
if [ -n "${_seed_cid:-}" ]; then
  PREV_KC=$(podman inspect "$_seed_cid" \
              --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
            | sed -n 's#^KEYCLOAK=http://\([^:]*\):.*#\1#p' | head -1) || true
fi
RECREATE_APPS=0
if [ -n "$PREV_KC" ] && [ "$PREV_KC" != "$KC_ADDR" ]; then
  echo "up.sh: issuer changes ${PREV_KC} -> ${KC_ADDR}; recreating the apps so they re-read it"
  RECREATE_APPS=1
fi

# docker compose brings the whole stack up from a single `up -d`, so keep that path exactly
# as-is. podman-compose does NOT: on `up -d` it starts the backing services and the early
# one-shots, then routinely hangs or exits 125 leaving vault-seed, the four apps and the demo
# stuck in `Created` — it fails to start the second wave of services that sit behind completed
# one-shots. So on podman we instead CREATE every container with `--no-start` (which it does
# reliably and deterministically) and then start the services ourselves, in dependency order,
# exactly the order the app entrypoints already expect. This also subsumes the issuer-change
# recreate below: a changed KC_ADDR changes vault-seed/app config hashes, so `up --no-start`
# recreates just those into `Created`, and the ordered start re-seeds and re-reads the issuer.
# --remove-orphans on every bring-up, so the RUNNING stack always matches the one you asked
# for. Without it compose leaves behind any container whose service is no longer in the
# selected files — it does not stop them, it just stops managing them. Two ways that bites:
# a service that gets disabled (datahub-cleanup) keeps running and keeps costing its ~650MB,
# and `--no-demo` leaves demo-feed happily writing datapoints. Both look like nothing happened.
if [ "${COMPOSE[0]}" != "podman" ]; then
  "${COMPOSE[@]}" ${COMPOSE_FILES[@]+"${COMPOSE_FILES[@]}"} up -d --remove-orphans ${ARGS[@]+"${ARGS[@]}"}
  rc=$?
  if [ "$rc" -eq 0 ] && [ "$RECREATE_APPS" -eq 1 ]; then
    "${COMPOSE[@]}" ${COMPOSE_FILES[@]+"${COMPOSE_FILES[@]}"} up -d --force-recreate --no-deps \
      datahub-api datahub-console datahub-stateless-consumer datahub-stateful-consumer
    rc=$?
  fi
  exit "$rc"
fi

# ---------------------------------------------------------------------------- podman path ---
PROJECT="${COMPOSE_PROJECT_NAME:-datahub}"   # matches `name: datahub` in docker-compose.yml

# --build is handled explicitly here (build, then create) rather than forwarded to
# `up --no-start`, so the create step never has to decide between build-vs-pull.
WANT_BUILD=0
CREATE_ARGS=()
for a in ${ARGS[@]+"${ARGS[@]}"}; do
  if [ "$a" = "--build" ]; then WANT_BUILD=1; else CREATE_ARGS+=("$a"); fi
done

# Container id(s) for a compose service in this project. Empty when the service isn't in the
# selected compose files (e.g. demo-* under --no-demo), which makes every call below a no-op.
_svc_ids() {
  podman ps -aq --filter "label=com.docker.compose.project=${PROJECT}" \
                --filter "label=com.docker.compose.service=$1" 2>/dev/null
}
# Start a service if it exists; a no-op on one already running (idempotent re-runs).
_start_svc() { local id; id=$(_svc_ids "$1"); [ -n "$id" ] && podman start $id >/dev/null 2>&1 || true; }
# Wait (bounded, ~4 min) for a one-shot to exit so the next wave sees its results.
_wait_exit() {
  local id; id=$(_svc_ids "$1"); [ -z "$id" ] && return 0
  local n=0
  while [ "$(podman inspect $id --format '{{.State.Status}}' 2>/dev/null)" != "exited" ]; do
    n=$((n + 1)); [ "$n" -gt 120 ] && { echo "up.sh: one-shot '$1' did not finish in time" >&2; return 1; }
    sleep 2
  done
}

# Podman stores a locally BUILT image as `localhost/<name>` but resolves the compose files'
# unqualified `image:` names through unqualified-search-registries — so a leftover
# `docker.io/library/datahub-*:local` can shadow the image just built, and the stack silently
# runs a stale one after a successful `--build`. Warn rather than delete: not ours to remove.
_shadowing=$(podman images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null \
             | grep -E '^docker\.io/library/datahub-.*:local$' || true)
if [ -n "$_shadowing" ]; then
  echo "up.sh: WARNING — these images can shadow the ones compose builds:" >&2
  printf '  %s\n' $_shadowing >&2
  echo "  Remove them so the freshly built images are used:  podman rmi $_shadowing" >&2
fi

if [ "$WANT_BUILD" -eq 1 ]; then
  echo "up.sh: building images (jars built inside the image; no host JDK)"
  "${COMPOSE[@]}" ${COMPOSE_FILES[@]+"${COMPOSE_FILES[@]}"} build \
    || { echo "up.sh: image build failed" >&2; exit 1; }
fi

# 1) Create every container without starting it — the one thing podman-compose does reliably.
#    Its create step can still intermittently exit 125 (e.g. a network-setup race right after a
#    teardown); it is idempotent, so retry. `if` keeps `set -e` from aborting on a failed try.
rc=1
for attempt in 1 2 3 4 5; do
  if "${COMPOSE[@]}" ${COMPOSE_FILES[@]+"${COMPOSE_FILES[@]}"} up --no-start --remove-orphans ${CREATE_ARGS[@]+"${CREATE_ARGS[@]}"}; then
    rc=0; break
  fi
  rc=$?
  echo "up.sh: 'compose up --no-start' failed ($rc); retry ${attempt}/5 in 3s…" >&2
  sleep 3
done
[ "$rc" -eq 0 ] || { echo "up.sh: could not create the stack after 5 attempts" >&2; exit "$rc"; }

echo "up.sh: podman-compose won't start the full stack itself — starting services in order"
# 2) Backing services.
for s in postgres clickhouse neo4j valkey kvrocks pulsar vault keycloak; do _start_svc "$s"; done
# 3) Vault: auto-init/unseal, then seed. The apps AppRole-login with what vault-seed writes,
#    so let it finish before they try.
_start_svc vault-init; _wait_exit vault-init
_start_svc vault-seed; _wait_exit vault-seed
# 4) The other one-shots (idempotent; the apps also self-wait on these via their entrypoints).
_start_svc pulsar-init
_start_svc keycloak-bootstrap
# 5) The apps. datahub-analysis (:8082) joins the four originals — it backs the console's Analyze
#    tab and "related series" panel, which the browser calls directly, so it must be up. Each app
#    self-waits on datahub-api via its entrypoint, so start order within this loop does not matter.
APP_SVCS="datahub-api datahub-console datahub-stateless-consumer datahub-stateful-consumer datahub-analysis"
for s in $APP_SVCS; do _start_svc "$s"; done
# 5b) If the issuer changed, RESTART the apps — starting them is not enough. An app reads
#     keycloak.issuer from Vault once, at boot, and vault-seed has just rewritten it. On a fresh
#     create that is fine, but on a stack already running `_start_svc` is a no-op, so the apps
#     keep validating the OLD issuer and reject every token. The docker path force-recreates for
#     this; here the app services never reference KC_ADDR (only vault-seed does), so their config
#     hash never changes and compose leaves them alone.
if [ "$RECREATE_APPS" -eq 1 ]; then
  echo "up.sh: issuer changed — restarting the apps so they re-read it from Vault"
  for s in $APP_SVCS; do
    id=$(_svc_ids "$s"); [ -n "$id" ] && podman restart $id >/dev/null 2>&1 || true
  done
fi
# 6) The demo (present only under --demo): seed first — waiting so a successful `up.sh` means
#    the data is actually there — then start the live feed.
if [ -n "$(_svc_ids demo-seed)" ]; then
  echo "up.sh: seeding the 3W demo (this waits for datahub-api to finish booting)…"
  _start_svc demo-seed; _wait_exit demo-seed
  seed_state=$(podman inspect $(_svc_ids demo-seed) --format '{{.State.ExitCode}}' 2>/dev/null)
  if [ "${seed_state:-1}" = "0" ]; then echo "up.sh: demo seeded."; else
    echo "up.sh: WARNING demo-seed exited ${seed_state:-?} — check 'podman logs ${PROJECT}_demo-seed_1'" >&2
  fi
  _start_svc demo-feed
fi

echo "up.sh: stack is up."
exit 0
