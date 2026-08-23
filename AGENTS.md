# AGENTS.md
Guidance for AI coding agents working in this repository. Claude Code reads it through the `CLAUDE.md` import alongside it.

## Project Overview

DataHub Platform is a multi-module Spring Boot 4 (Java 25) application for managing datasets, time-series data, resources, and streaming data. It uses PostgreSQL, Apache Pulsar for messaging, and ClickHouse for time-series storage.

## Documentation lives in two other repositories

Anything a person outside this repository can notice belongs in the docs: new or changed
features, renamed or removed configuration, changed defaults, changed setup or install steps,
new auth or access-control rules, changed API or SDK contracts. Pure internals (refactors,
performance work, tests, CI) do not.

The documentation is split by audience across two separate public repositories:

- [datahub-docs](https://github.com/IntelliStream-DataHub/datahub-docs) — operators and administrators
- [datahub-sdk-docs](https://github.com/IntelliStream-DataHub/datahub-sdk-docs) — developers using the SDKs or REST API

A change may belong in one, both or neither. Putting developer detail in the operator site is
the most common mistake.

**You are not expected to have those repositories checked out**, and updating them is not a
precondition for contributing here. Saying in the pull request which docs a change affects, or
opening an issue on the relevant repository, is enough. If it is genuinely unclear which site a
change belongs to, say so rather than guessing.

> **Maintainers:** run the `docs-check` skill before committing a user-visible change. It
> automates the routing above and the follow-up commit in each docs repository. It lives with
> the website that publishes both sites, not in this repository, so it is unavailable to most
> contributors and is not a precondition for contributing.

## Licensing

The platform is AGPL-3.0-or-later, and every source file carries
`// SPDX-License-Identifier: AGPL-3.0-or-later` as its first line.

**Two modules are Apache-2.0 instead**: `datahub-api-model` and `datahub-java-sdk`. They are
linked into other people's applications, which copyleft would prevent, so their files carry
`// SPDX-License-Identifier: Apache-2.0` and each module has its own LICENSE. A new file in
either module takes the Apache header; a new file anywhere else takes the AGPL one. Do not add
an AGPL-licensed dependency to either module, since that would defeat the split.

## Constraints

[CONSTRAINTS.md](CONSTRAINTS.md) records the invariants this codebase is meant to hold: Postgres
as the authoritative store, validation before anything goes async, one type label per node, and
on the frontend, calling datahub-api directly rather than extending the console's
backend-for-frontend proxy. Read it before adding a feature that touches any of those.

## Build Commands

```bash
./gradlew build                        # Build all modules
./gradlew test                         # Run all tests
./gradlew :datahub-api:test            # Run tests for a single module
./gradlew test --tests "*.EventServiceTest"  # Run a single test class
./gradlew :datahub-api:bootRun         # Run API server (port 8081)
./gradlew :datahub-stateless-consumer:bootRun  # Run datapoint/event consumer
./gradlew :datahub-stateful-consumer:bootRun   # Run graph (Neo4j) consumer
./gradlew :datahub-console:bootRun     # Run console UI
./gradlew :datahub-analysis:bootRun    # Run timeseries analysis compute service (port 8082)
./gradlew startBootStack               # Run api, both consumers, analysis service, and console in parallel
./gradlew --console plain jshell       # Interactive JShell (useful for IdGenerator.xxHash())
```

Flyway tasks (datahub-infra owns the scripts + plugin):
```bash
./gradlew :datahub-infra:flywayInfo
./gradlew :datahub-infra:flywayMigrate
```

## Module Structure

- **datahub-api** — REST API (Spring Boot web, port 8081). OAuth2 resource server, Swagger UI at `/swagger-ui.html`. Provisions each tenant's Postgres schema **on demand** (boot, hot-load `TenantAddedEvent`, and first request for an unconfirmed org via `TenantProvisioningFilter`) through the shared `TenantMigrationService`; it attempts once and does **not** retry on a timer — datahub-cleanup owns the persistent retry.
- **datahub-stateless-consumer** — Headless Spring Boot app. Consumes datapoint/event Pulsar messages, writes batched inserts to ClickHouse, and fans datapoints out to WebSocket subscription topics. Scales horizontally. Its only port (9083) serves the Prometheus scrape.
- **datahub-stateful-consumer** — Headless Spring Boot app. Consumes resource CUD Pulsar messages and applies them to the Neo4j knowledge graph. Order-sensitive; runs with failover rather than fan-out. Its only port (9084) serves the Prometheus scrape.
- **datahub-console** — Server-side rendered web UI (Thymeleaf + vanilla JS). OAuth2 client auth. PostCSS for CSS minification.
- **datahub-analysis** — Timeseries relationship-analysis service (Spring Boot web, port 8082). Owns the `POST /analysis` endpoint: the console's Analyze tab posts an `AnalysisForm` directly (browser-facing, so CORS is enabled for the console origin), and this service gathers its own data — nearest-N graph BFS (`/resources/fetch-nearest`) and ClickHouse-aggregated series (`/timeseries/data/list`) — from the api **via the Java SDK (`datahub-java-sdk`), forwarding the caller's JWT** so the api's per-dataset ACLs apply. It then runs the numeric engine in-process and returns raw/whitened cross-correlation, cointegration, stability, and Welch coherence. Stateless, no DB. Trust is OAuth2 — a resource server validating the **user's own JWT** (requires `ROLE_DATAHUB_ACCESS`) against the issuer it loads from Vault at startup (shared `datahub-platform` secret, like the api/console; run with `SPRING_PROFILES_ACTIVE=dev|prod`). Point it at the api with `datahub.api.url`. Owns the numeric engine (`ai.intellistream.datahub.analysis`, on commons-math3). Scales horizontally.
- **datahub-infra** — Shared JPA entities, repositories, services. Neo4j graph operations, Redis/Lettuce caching, ClickHouse client.
- **datahub-commons** — Minimal-dependency shared library: DTOs, form models, validators, utilities (UUID v7, hashing, LZ4/Zstd compression). No Spring Boot starters.
- **datahub-api-model** — Framework-free wire-contract library: the request/response DTOs, form models, and envelopes that make up the datahub-api REST contract. Sits below `commons` (`commons` depends on it with `api`, so existing imports resolve unchanged). No frameworks in the artifact, so the lean Java SDK can consume it. Not published to a public Maven repository yet; out-of-tree consumers install it with `publishToMavenLocal`.
- **datahub-java-sdk** — Thin synchronous Java client for the REST API (JDK `HttpClient` + Jackson 3 — no Spring/Feign). Depends only on `api-model`; published as `ai.intellistream:datahub-sdk`. Optional durable disk-spool buffering for datapoint/event ingest. Has its own `AGENTS.md`.
- **datahub-pulsar-filter** — Pulsar broker-side `EntryFilter` plugin (NAR archive). Filters fan-out topic messages per WebSocket subscription on dispatch. Deployed to Pulsar brokers, not run as a service.
- **datahub-cleanup** — Scheduled housekeeping app (single instance). Prunes stale file-storage temp files + folders of removed tenants (`cleanup.file` package), sweeps orphaned Pulsar subscriptions (`cleanup.subscription`), and runs the per-tenant Flyway **migration self-heal sweep** (`cleanup.migration`): a boot pass over all tenants plus a scheduled retry of any that failed, with per-tenant exponential backoff, via the shared `TenantMigrationService`. This is the sole persistent retrier — datahub-api provisions on demand and gives up after one attempt. Depends on `infra` + `commons`.
- **buildSrc** — Gradle convention plugins (`java-common-conventions`, `java-library-conventions`, `java-application-conventions`).

Dependency flow: `api`/`consumers`/`console` depend on `infra` and `commons`. `infra` depends on `commons`; `commons` depends on `api-model`. `java-sdk` depends only on `api-model`, and `analysis` calls the api through `java-sdk`.

## Key Technology

- **Build:** Gradle 9.4.1+, Java 25 toolchain
- **Framework:** Spring Boot 4.1.0, Spring Cloud 2025.1.1
- **Auth:** OAuth2 Resource Server (JWT) with Vault for secrets
- **Messaging:** Apache Pulsar 4.0.11 (OAuth2 auth)
- **Serialization:** Jackson 3.x (`jacksonVersion` in `gradle.properties`)
- **DB migrations:** Flyway 11.x (`datahub-infra/src/main/resources/db/migration/`, shared by datahub-api's on-demand provisioning and datahub-cleanup's self-heal sweep). The `flywayMigrate`/`flywayInfo` Gradle tasks live in datahub-infra alongside the scripts (`./gradlew :datahub-infra:flywayMigrate`).
- **Tests:** JUnit 5 with Spring Boot Test, Testcontainers

## Shared Version Properties

All shared versions are centralized in the **root `gradle.properties`** (propagated to every
subproject) — change a version in one place:
- Library versions: `pulsarVersion`, `jacksonVersion`, `feignVersion` — referenced in module
  `build.gradle` as `${pulsarVersion}` etc.
- Plugin versions: `springBootVersion`, `dependencyManagementVersion`, `nodeGradleVersion` —
  consumed by `settings.gradle` `pluginManagement`, so modules apply these plugins without a
  version. Do not re-add versions to module plugin blocks.

## Architecture Notes

- **Multi-tenancy** is supported via `StatelessRoutingDataSource` and `TenantContext`. `RequestStateCleanupFilter` in `datahub-api` clears the `TenantContext` and memoised dataset-permission ThreadLocals at the end of every HTTP request. Do not remove: tenant state and one caller's dataset grants would both leak across pooled threads.
- **Security** is stateless (no sessions on API). JWT tokens carry roles; the security filter chain (`SecurityConfig`) requires `ROLE_DATAHUB_ACCESS` on every non-public endpoint — REST controllers and the MCP tools alike. Key role: `DATAHUB_ACCESS`. (`@EnableMethodSecurity` is on, so `@PreAuthorize` is available for finer-grained per-method rules, but the baseline role gate lives in the filter chain, not in annotations.)
- **Dataset ACLs**: all dataset grants are Keycloak **organization groups**, read from the UserInfo endpoint and cached: per-dataset grants (`/datasets/<externalId>/read|write`) are expanded down the `BELONGS_TO` dataset hierarchy so a grant on a parent covers its descendants, and all-datasets access is the wildcard pair `/datasets/*/read|write` (organization-scoped, no expansion). The only realm role left in the ACL is `DATAHUB_ADMIN`, the cross-tenant operator escape hatch, resolved from the token alone. The id-bearing `DATAHUB_DATASET_READ_<id>` roles and the blanket `DATAHUB_DATASET_ALL`/`_READ_ALL`/`_WRITE_ALL` roles are gone. Creating/updating/deleting a **dataset itself** requires an all-datasets write grant (`DataSecurity.assertCanManageDataSets()`), deliberately: a dataset is the unit access is granted on, so re-parenting or renaming one changes what existing grants cover. See `datahub-api/DATASET_ACL_SETUP.md` and `datahub-api/KEYCLOAK_ORG_GROUPS.md`. The dev realm's post-import step (`deploy/keycloak/bootstrap-org-groups.sh`) is applied automatically by the `keycloak-bootstrap` one-shot service in compose, and clients must request `scope=organization:*`.
- **Hibernate** batch size is 1000 with ordered inserts/updates. DDL mode is `none` (Flyway handles schema). Virtual threads enabled.
- **Graph operations** use Neo4j with Cypher DSL. Entities have `Node` and `Edge` abstractions with typed relationships.
- **Pulsar topics**: `datahub/datapoints`, `datahub/resources`, `datahub/events`. See `datahub-api/PULSAR_SETUP.md` for namespace/topic config.

## Deployment & Scaling

`api`, `console`, and the stateless consumer can run as multiple instances behind a load balancer. See [README.md](README.md) for operational detail. Key constraints:

- **Metrics**: every service serves Prometheus metrics at `/actuator/prometheus` with no token, on a port of its own (api 9081, console 9080, analysis 9082, stateless consumer 9083, stateful consumer 9084, cleanup 9085) that is opened to the Prometheus host only. The three web services put it on a separate management port so the load balancer never reaches it; an `@Order(1)` chain in each `SecurityConfig` permits the scrape and denies every other actuator path. The health endpoint is not exposed and its indicators are off (`management.health.defaults.enabled=false`): the tenant-routing datasource cannot be probed without a tenant.
- **datahub-console** requires Valkey/Redis for Spring Session Redis (OAuth2 tokens, CSRF, locale all live in the externalized session). Credentials come from the `http.session.valkey.*` fields of Vault secret `intellistream-datahub/datahub-console`.
- **datahub-api** is stateless, including its two WebSocket endpoints under `/timeseries/datapoints/`: `.../subscription/listen/**` (`SubscriptionWebSocketHandler`, durable subscriptions over a Bearer-JWT handshake, multiplexes several subscriptions per socket and routes ack/nack per subscription) and `.../listen` (`DatapointListenWebSocketHandler`, browser live tail authed via a `?token=` query param). **Neither needs session affinity** — the subscription cursor lives in Pulsar (`Failover`/`Key_Shared`) and the tail is a non-durable `latest` consumer, so a reconnect resumes on any instance. The load balancer only needs to support WebSocket upgrades and long-lived connections.
- **datahub-stateless-consumer** is stateless; scale by adding instances (Pulsar subscription type coordinates distribution).
- **datahub-stateful-consumer** applies order-sensitive graph mutations; run with Pulsar `Failover` subscription rather than scaling out.

## Package Structure

API, consumers, infra, and commons share the base package `ai.intellistream.datahub`. Console uses `ai.intellistream.dhconsole`.

Key layers in datahub-api: `controllers/` (REST endpoints), `services/` (business logic), `jpa/domains/` (entities), `jpa/repositories/`.

## Console Frontend

- **Thymeleaf templates:** `datahub-console/src/main/resources/templates/` — organized by feature (`resources/`, `datasets/`, `timeseries/`, `files/`, `events/`, `streams/`). Shared layout in `layout/main.html`.
- **i18n messages:** `datahub-console/src/main/resources/i18n/messages.properties` (English) and `messages_nb.properties` (Norwegian Bokmål). Keys are used in templates via `#{key.name}` and in Java via `MessageSource`.
- **Static assets:** `datahub-console/src/main/resources/static/` — `css/`, `js/` (vanilla JS organized by feature).
