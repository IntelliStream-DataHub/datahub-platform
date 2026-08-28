# datahub-infra

The shared **persistence and infrastructure** layer. It owns DataHub's access to every
backing store and wraps each in repositories and services the application modules
consume. Depends on `datahub-commons` and Spring; depended on by `datahub-api`,
and `datahub-stateless-consumer`.
_(Formerly `datahub-library`.)_

## What's inside

- **Relational — PostgreSQL via JPA/Hibernate** — entities in `jpa/domains/`,
  projections in `jpa/dto/`, and Spring Data repositories in `repositories/`
  (`node`, `label`, `unit`, `files`, `governance`, `subscription`, `stream`).
- **Graph — Neo4j** — `services/Neo4JService` and `config/Neo4j*`, using the Neo4j
  Java driver and Cypher DSL for the resource/edge graph.
- **Time-series — ClickHouse** — `clickhouse/` (`ClickHouseService`,
  `ClickHouseDatapointService`, `ClickHouseEventService`) over the ClickHouse v2 +
  HTTP clients.
- **Caching / KV** — `services/ValkeyService` (Lettuce/Redis) and
  `services/KVRocksService`.
- **Multi-tenancy** — `tenant/`: `StatelessRoutingDataSource` (routes each request to
  the caller's tenant datasource), `TenantContext`, and `TenantConfigService`.
- **Messaging & compression** — Pulsar payloads/producer plumbing in `pulsar/`, plus
  LZ4/Zstd compression (`lz4-java`, `zstd-jni`).
- **Mapping & helpers** — entity⇄DTO mapping in `transformers/` and shared code in
  `util/`.

## Dependencies of note

Neo4j driver + Cypher DSL, Lettuce, ClickHouse client v2, PostgreSQL JDBC, Pulsar
client/admin (with BouncyCastle for TLS — see the comment in `build.gradle`),
LZ4/Zstd, Tika, and Spring Boot `data-jpa`. Heavy infrastructure dependencies belong
here, not in `datahub-commons`.
