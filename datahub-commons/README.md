# datahub-commons

The shared **foundation** module: cross-cutting utilities and the server-side data
contracts that the rest of DataHub builds on. It depends on exactly one `datahub-*`
module — `datahub-api-model` — and re-exposes it transitively (`api`).

Depended on by `datahub-infra`, `datahub-api`, `datahub-console`,
`datahub-stateful-consumer`, and `datahub-stateless-consumer`.
_(Formerly `datahub-lib-nodep`.)_

> The lean, framework-free **wire-contract** types (the DTOs/forms/envelopes the REST
> API exposes) now live in [`datahub-api-model`](../datahub-api-model/README.md), the new
> bottom of the build graph. They keep the same package names, so imports are unchanged.
> What remains here is everything that needs more than a contract jar: server-side
> utilities, the OpenAPI mirror types, datapoint DTOs, Pulsar payloads, and the
> tenant/subscription infrastructure.

## What's inside

(The REST wire-contract types — `DataWrapper`, `GraphDataWrapper`, `Resource`,
`Timeseries`, the `*Form`/`*Search`/`*Retreiver` request models, the custom
`@Constraint` validators, etc. — moved to `datahub-api-model`. Commons keeps:)

- **Datapoint DTOs & OpenAPI mirrors** — the datapoint payloads in `api/responses/`
  (`Datapoint`, `DatapointsCollection`, `DataCollectionString`, …) and the springdoc
  mirror types in `api/responses/swaggerdto/`.
- **Pulsar & subscription payloads** — `pulsar/` message types and the `subscription/`
  models used by the streaming/WebSocket paths.
- **Vault client** — `config/`: `VaultProperties` (the `vault.*` settings, including the
  mTLS keystore/truststore), `VaultClientFactory` (the one place a Vault connection is
  opened) and `VaultConfigurationLoader`, the startup listener every application registers
  with its own `VaultSecretContributor`s.
- **Tenant infrastructure** — `tenant/`: `TenantContext`, `TenantConfigService` (Vault),
  and the per-backend tenant config records.
- **Utilities** — `helpers/`: `IdGenerator` (UUID v7 + xxHash/Blake3 keys), checksums,
  `ByteUnit`/color/text helpers, `HttpHelper`, and `EnvUtils`; plus `errors/`,
  `function/` and `config/`.

## Dependency policy

As the lowest layer, this module stays lean and free of persistence, messaging, and
web **infrastructure** — JPA, Neo4j, ClickHouse, Redis/Valkey, and the Pulsar client
belong in `datahub-infra`; Spring MVC / web concerns belong in the application modules.
What lives here: Jackson, Jakarta Validation, Lombok, the OpenFeign client interfaces,
small self-contained utilities (UUID/hashing), and OpenAPI **annotations only**
(`swagger-annotations`, for `@Schema` on the DTOs).

Notably **not** here:
- No `spring-boot-starter-web` / embedded Tomcat. The Spring MVC multipart resolver
  (`SmartMultipartResolver`) lives in `datahub-api`; `TenantConfigService` calls Vault
  with the JDK `HttpClient` rather than `RestTemplate`.
- No `springdoc-openapi` starter / Swagger UI — only the `swagger-annotations` jar.
  The web apps bring their own springdoc to serve `/swagger-ui.html`.
- `jakarta.servlet-api` is `compileOnly` (for `HttpHelper`), provided at runtime by the
  web app modules.
