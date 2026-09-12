#!/usr/bin/env bash
#
# Build the three app images CI needs, from boot jars already compiled on the host.
#
#   ./gradlew :datahub-api:bootJar :datahub-stateless-consumer:bootJar
#   ./scripts/ci-stage-images.sh
#
# Then bring the stack up WITHOUT --build, and compose will use these images
# instead of entering the Dockerfile's build stage:
#
#   docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d \
#     neo4j valkey kvrocks vault-seed pulsar-init keycloak-bootstrap \
#     datahub-api datahub-stateless-consumer
#
# The default tag is `local` precisely because that is what docker-compose.apps.yml
# already declares (`image: datahub-api:local`). Compose builds a service only when
# its image is missing or --build is passed, so tagging them here is the whole
# mechanism — no compose file needs to change, and CI boots the same stack
# developers do. See deploy/app/Dockerfile.ci for why building in-image is wrong
# for CI.
#
# Each image gets its own staging directory as its build context, because the root
# .dockerignore excludes **/build and would hide the jar. The console, the analysis
# service and the cleanup job are left out — no SDK calls any of them.
set -euo pipefail
cd "$(dirname "$0")/.."

MODULES=(datahub-api datahub-stateless-consumer)
TAG="${IMAGE_TAG:-local}"
OUT="build/ci-images"

# Default to podman where it exists (this repo's local workflow), docker otherwise.
# CI sets CONTAINER_CLI=docker explicitly.
CLI="${CONTAINER_CLI:-}"
if [ -z "$CLI" ]; then
  if command -v podman >/dev/null 2>&1; then CLI=podman; else CLI=docker; fi
fi

rm -rf "$OUT"

for m in "${MODULES[@]}"; do
  # Spring Boot's bootJar produces <module>-<version>.jar; the plain `jar` task
  # produces <module>-<version>-plain.jar, which is not runnable. Glob rather than
  # hardcode the version so a version bump does not silently break CI.
  shopt -s nullglob
  jar=""
  for candidate in "$m"/build/libs/"$m"-*.jar; do
    [ "${candidate%-plain.jar}" = "$candidate" ] || continue
    jar="$candidate"
    break
  done
  shopt -u nullglob

  if [ -z "$jar" ]; then
    echo "ci-stage-images.sh: no boot jar in $m/build/libs/" >&2
    echo "  run: ./gradlew :$m:bootJar" >&2
    exit 1
  fi

  mkdir -p "$OUT/$m"
  cp "$jar" "$OUT/$m/app.jar"
  cp deploy/app/entrypoint.sh "$OUT/$m/entrypoint.sh"
  cp deploy/app/Dockerfile.ci "$OUT/$m/Dockerfile"

  echo "==> $m:$TAG  (from $(basename "$jar"))"
  "$CLI" build -t "$m:$TAG" "$OUT/$m"
done

echo
echo "Built with $CLI: ${MODULES[*]/%/:$TAG}"
