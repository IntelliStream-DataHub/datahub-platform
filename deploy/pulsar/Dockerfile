# Pulsar for the local dev stack, with the DataHub EntryFilter baked in.
#
# datahub-pulsar-filter is a broker-side plugin, not a service: it filters dispatched entries
# against a subscription-scoped property so one partitioned fan-out topic can serve many
# WebSocket subscribers. Without it loaded on the broker, subscribers receive messages that
# belong to OTHER subscriptions — the api still answers every REST call correctly, so this
# shows up as a data-isolation bug rather than an outage.
#
# Same shape as deploy/neo4j/Dockerfile (APOC baked in, pinned, no auto-download), except the
# artifact is built from this repo rather than downloaded: a JDK stage runs the module's `nar`
# task, and the runtime stage copies the result into the broker's filter directory. The
# matching entryFiltersDirectory / entryFilterNames settings live on the service in
# docker-compose.yml, exactly as the allowlist does for neo4j.

# ---- build stage: produce the NAR (no host JDK or ./gradlew step needed) ----
FROM docker.io/library/eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :datahub-pulsar-filter:nar

# ---- runtime: stock broker + the plugin ----
# Keep this in step with pulsarVersion in gradle.properties, which the filter is built and
# integration-tested against, and with the stock pulsar images in docker-compose.apps.yml.
FROM docker.io/apachepulsar/pulsar:4.0.11
# The name inside entry_filter.yml must match entryFilterNames in the broker config; the
# filename does not, but keep it recognisable.
COPY --from=build /src/datahub-pulsar-filter/build/distributions/*.nar \
     /pulsar/filters/datahub-pulsar-filter.nar
