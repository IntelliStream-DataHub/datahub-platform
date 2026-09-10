# Architecture

This document describes the module layout, build commands, and architectural
conventions of IntelliStream DataHub for developers working on the codebase.
For the product overview and value proposition see [README.md](README.md); for
standing up a local environment see [GETTING_STARTED.md](GETTING_STARTED.md).

## Overview

DataHub is a multi-module Spring Boot 4 (Java 25) platform for managing
datasets, time-series data, resources, and streaming data. It uses PostgreSQL
(relational/ontology storage), Apache Pulsar (messaging), ClickHouse
(time-series), Neo4j (knowledge graph), Apache Kvrocks (event key mappings),
and Valkey/Redis (sessions/cache). Authentication is OAuth2 (JWT); secrets are
managed in HashiCorp Vault.

## Build commands

```bash
./gradlew build                        # Build all modules
./gradlew test                         # Run all tests
./gradlew :datahub-api:test            # Run tests for a single module
./gradlew test --tests "*.EventServiceTest"  # Run a single test class
./gradlew :datahub-api:bootRun         # Run API server (port 8081)
./gradlew :datahub-stateless-consumer:bootRun  # Run datapoint/event consumer
./gradlew :datahub-console:bootRun     # Run console UI
./gradlew startBootStack               # Run api, both consumers, and console in parallel
./gradlew --console plain jshell       # Interactive JShell (useful for IdGenerator.xxHash())
```

Flyway tasks (datahub-api only):

```bash
./gradlew :datahub-api:flywayInfo
./gradlew :datahub-api:flywayMigrate
```

The build needs only a JDK-capable machine — the Gradle wrapper (`./gradlew`)
provisions Gradle 9.4.1 and the Java 25 toolchain. The console's CSS/JS assets
are built on the JVM (no Node.js required); see
[datahub-console/ASSETS.md](datahub-console/ASSETS.md).

## Module structure

- **datahub-api** — REST API (Spring Boot web, port 8081). OAuth2 resource server, Swagger UI at `/swagger-ui.html`. Owns Flyway migrations. See [datahub-api/README.md](datahub-api/README.md).
- **datahub-stateless-consumer** — Headless Spring Boot app. Consumes datapoint/event Pulsar messages, writes batched inserts to ClickHouse, and fans datapoints out to WebSocket subscription topics. Scales horizontally. No web server.
- **datahub-console** — Server-side rendered web UI (Thymeleaf + vanilla JS). OAuth2 client auth.
- **datahub-infra** — Shared JPA entities, repositories, services. Neo4j graph operations, Redis/Lettuce caching, ClickHouse client.
- **datahub-commons** — Minimal-dependency shared library: DTOs, form models, validators, utilities (UUID v7, hashing, LZ4/Zstd compression). No Spring Boot starters.
- **datahub-pulsar-filter** — Pulsar broker-side `EntryFilter` plugin (NAR archive). Filters fan-out topic messages per WebSocket subscription on dispatch. Deployed to Pulsar brokers, not run as a service.
- **datahub-cleanup** — Scheduled housekeeping app (single instance). Prunes stale file-storage temp files and folders of removed tenants (`cleanup.file`), and sweeps orphaned Pulsar subscriptions whose backlog would otherwise trip the fan-out quota (`cleanup.subscription`). Depends on `infra` + `commons`.
- **buildSrc** — Gradle convention plugins (`java-common-conventions`, `java-library-conventions`, `java-application-conventions`).

Dependency flow: `api`/`consumers`/`console` depend on `infra` and `commons`.
`infra` depends on `commons`.

## Key technology

- **Build:** Gradle 9.4.1+, Java 25 toolchain
- **Framework:** Spring Boot 4.0.x, Spring Cloud 2025.1.x
- **Auth:** OAuth2 Resource Server (JWT) with Vault for secrets
- **Messaging:** Apache Pulsar 4.0.x (OAuth2 auth in production; plaintext for local dev)
- **Serialization:** Jackson 3.x
- **DB migrations:** Flyway 11.x (`datahub-api/src/main/resources/db/migration/`)
- **Tests:** JUnit 5 with Spring Boot Test, Testcontainers

## Shared version properties

All shared versions are centralized in the **root `gradle.properties`**
(propagated to every subproject) — change a version in one place:

- Library versions: `pulsarVersion`, `jacksonVersion`, `feignVersion` — referenced in module `build.gradle` as `${pulsarVersion}` etc.
- Plugin versions: `springBootVersion`, `dependencyManagementVersion`, `nodeGradleVersion` — consumed by `settings.gradle` `pluginManagement`, so modules apply these plugins without a version. Do not re-add versions to module plugin blocks.

## Configuration & secrets

At startup each service runs a `VaultConfigurationLoader` (an
`ApplicationEnvironmentPreparedEvent` listener) that authenticates to Vault via
AppRole and loads the global secrets it needs (Pulsar, plus on the console the
session-store and OAuth2 client config) into the Spring environment before the
context initializes. Per-tenant backend connections (PostgreSQL, ClickHouse,
Neo4j, Valkey, KVRocks) are not loaded here; they are resolved at runtime from
the per-tenant registry by `TenantConfigService`. The Vault paths and keys each
service reads are documented in [GETTING_STARTED.md](GETTING_STARTED.md#vault-contract).

The Vault address and AppRole credentials themselves come from
`application-{profile}.yml` (gitignored; see the `*.yml.example` templates) or
environment variables (`VAULT_ADDRESS`, `VAULT_ROLE_ID`, `VAULT_SECRET_ID`).
A Vault listener that requires mutual TLS is reached by pointing
`vault.keystore` / `vault.keystore-password` (`VAULT_KEYSTORE`,
`VAULT_KEYSTORE_PASSWORD`) at a PKCS12 holding the client certificate, and
optionally `vault.truststore` (`VAULT_TRUSTSTORE`) at the CA that signed
Vault's server certificate. The same stores serve the startup loader and
`TenantConfigService`'s periodic refresh.

## Architecture notes

- **Multi-tenancy** is supported via `StatelessRoutingDataSource` and `TenantContext`. The per-tenant connection registry is read from Vault (`<secret-name>/tenant-resources`). `RequestStateCleanupFilter` in `datahub-api` clears the `TenantContext` and memoised dataset-permission ThreadLocals at the end of every HTTP request. Do not remove: tenant state and one caller's dataset grants would both leak across pooled threads.
- **Security** is stateless (no sessions on API). JWT tokens carry roles; method-level security via `@PreAuthorize`. Key role: `DATAHUB_ACCESS`. Per-dataset ACLs are Keycloak organization groups, inherited down the dataset hierarchy; blanket all-datasets grants stay realm roles. See [datahub-api/DATASET_ACL_SETUP.md](datahub-api/DATASET_ACL_SETUP.md) for what they gate and [datahub-api/KEYCLOAK_ORG_GROUPS.md](datahub-api/KEYCLOAK_ORG_GROUPS.md) for the Keycloak setup.
- **Hibernate** batch size is 1000 with ordered inserts/updates. DDL mode is `none` (Flyway handles schema). Virtual threads enabled.
- **Graph operations** use Neo4j with Cypher DSL. Entities have `Node` and `Edge` abstractions with typed relationships.
- **Pulsar topics**: `{tenant}/datapoints`, `{tenant}/resources`, `{tenant}/events`. See [datahub-api/PULSAR_SETUP.md](datahub-api/PULSAR_SETUP.md) for namespace/topic config.

## Deployment & scaling

`api`, `console`, and the stateless consumer can run as multiple instances
behind a load balancer. See [README.md](README.md#deployment--scaling) for the
full operational detail. Key constraints:

- **datahub-console** requires Valkey/Redis for Spring Session Redis (OAuth2 tokens, CSRF, locale all live in the externalized session).
- **datahub-api** is stateless. Its WebSocket endpoints under `/timeseries/datapoints/` (`.../subscription/listen/**` for durable subscriptions, `.../listen` for the browser live tail) need **no** session affinity — the subscription cursor lives in Pulsar (`Failover`) and the tail is a non-durable `latest` consumer, so a reconnect resumes on any instance. The load balancer only needs WebSocket-upgrade support.
- **datahub-stateless-consumer** is stateless; scale by adding instances (Pulsar subscription type coordinates distribution).
- **The Neo4j graph mirror** is applied by datahub-api from the per-tenant `resource_outbox` table, serialised by a Postgres advisory lock rather than by a single-instance deployment.

## Package structure

API, consumers, infra, and commons share the base package
`ai.intellistream.datahub`. Console uses `ai.intellistream.dhconsole`.

Key layers in datahub-api: `controllers/` (REST endpoints), `services/`
(business logic), `jpa/domains/` (entities), `jpa/repositories/`.

## Console frontend

- **Thymeleaf templates:** `datahub-console/src/main/resources/templates/` — organized by feature (`resources/`, `datasets/`, `timeseries/`, `files/`, `events/`, `streams/`). Shared layout in `layout/main.html`.
- **i18n messages:** `datahub-console/src/main/resources/i18n/messages.properties` (English) and `messages_nb.properties` (Norwegian Bokmål). Keys are used in templates via `#{key.name}` and in Java via `MessageSource`.
- **Static assets:** `datahub-console/src/main/resources/static/` — `css/`, `js/` (vanilla JS organized by feature). Build pipeline documented in [datahub-console/ASSETS.md](datahub-console/ASSETS.md).
