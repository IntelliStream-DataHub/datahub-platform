# Runtime image for a DataHub Spring Boot service (LOCAL DEV) — builds the boot
# jars INSIDE the image, so the host needs only podman/docker (no JDK, no
# `./gradlew` step). `./scripts/up.sh --build` is the whole thing.
#
# Multi-stage:
#   1. build stage — a JDK 25 toolchain compiles ALL app boot jars. This stage is
#      byte-identical for every app service (it doesn't use MODULE), so the build
#      tool caches it and Gradle runs ONCE; the four runtime images just copy
#      their own jar out of it.
#   2. runtime stage — JRE 25 + the module's jar + an entrypoint that waits for
#      Vault to be seeded, Postgres/Pulsar to be ready, etc. before launching.

# ---- build stage: compile every app boot jar in a JDK 25 container ----
FROM docker.io/library/eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# --no-daemon: one-shot build, no lingering daemon in the layer. Builds all app
# bootJars (incl. the console's PostCSS/node step, provisioned by node-gradle).
RUN ./gradlew --no-daemon clean bootJar

# ---- runtime stage: JRE + this module's jar + the shared entrypoint ----
FROM docker.io/library/eclipse-temurin:25-jre

# curl: used by the entrypoint to poll Vault for the seeded tenant registry.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Which module's jar this image runs (datahub-api / datahub-console /
# datahub-stateless-consumer / datahub-analysis / datahub-cleanup).
ARG MODULE
COPY --from=build /src/${MODULE}/build/libs/${MODULE}-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=build /src/deploy/app/entrypoint.sh /usr/local/bin/entrypoint
RUN chmod +x /usr/local/bin/entrypoint

ENTRYPOINT ["/usr/local/bin/entrypoint"]
CMD ["java", "-jar", "/app/app.jar"]
