# datahub-java-sdk

Thin, synchronous Java client for the DataHub Platform REST API, published as
`ai.intellistream:datahub-sdk` for out-of-tree consumers. Usage docs: [README.md](README.md).

## Hard constraints

- **No server stack.** Built on the JDK `java.net.http.HttpClient`; depends only on
  `datahub-api-model` (the wire contract) plus Jackson 3. No Spring, no Feign, no Vault client —
  the Spring Boot plugins in `build.gradle` exist only for BOM version management. Keep the
  dependency surface at zero-/tiny-transitive jars; this artifact ships to external users.
- **Wire types come from `datahub-api-model`** — never redefine request/response DTOs here.
  In-tree it is a project dependency (`api project(':datahub-api-model')`); the published POM
  pins resolved versions so non-Spring consumers work.
- **Events ingested without an id get a UUID v7** (`util/UuidV7`) stamped client-side before the
  first send, so a retry carries the same id and collapses in ClickHouse
  (`ReplacingMergeTree ORDER BY id`). Never switch to random v4 ids for events — they scatter
  the sort key and degrade insert/merge/query performance.
- **The durable spool must stay memory-safe** (`ingest/DurableSpool`): append to a plain NDJSON
  active segment, gzip-seal at ~50 MiB rollover, stream sealed segments in fixed-size chunks on
  flush — a multi-gigabyte spool never loads into memory. Buffer only retryable failures:
  unreachable (network error, 429, 5xx) and auth (401/403). Terminal errors such as 400 are
  surfaced, never buffered. The one 403 that is **not** buffered is a tenant that has reached a
  permanent ceiling (`type` ends `/errors/tenant-limit-reached`): replaying it can never succeed,
  so buffering would fill the spool with refused data and bury the message saying the limit is
  raised by asking. Bounded by `bufferRetention` (time) and `bufferMaxBytes` (size); off by default.

## Layout (`ai.intellistream.datahub.sdk`)

- `client/` — `DatahubClient` (entry point, one accessor per service), `DatahubConfig`
  (builder; `fromEnv()` on `BASE_URL` + `TOKEN` or `CLIENT_ID`/`CLIENT_SECRET`/`TOKEN_URI`,
  optionally `SCOPE`/`AUDIENCE` and the `ASSERTION*` keys that select the `jwt-bearer` grant;
  Vault variants via `VaultSecretLoader`, a JDK-HttpClient KV v2 read supporting token and
  AppRole auth).
- `auth/` — `TokenProvider`: static token pass-through, or a cached single-flight exchange
  refreshed ~30 s before expiry — client-credentials, or the RFC 7523 `jwt-bearer` grant when an
  assertion source is configured. The assertion is re-requested per exchange, never cached,
  because providers commonly reject a replayed one.
- `http/` — shared plumbing: `ApiHttp` request helpers, `DatahubApiException` error mapping.
- `services/` — one class per API area: resources, timeseries, datasets, events, units, files,
  subscriptions.
- `ingest/` — batched ingestion plus the durable disk spool (`DatapointIngestor`,
  `EventIngestor`, `DurableSpool`, `BatchExecutor`).
- `subscriptions/` — `SubscriptionListener`: durable subscription listening over the api's
  WebSocket endpoint with per-subscription ack/nack.
- `timeseries/`, `util/` — `Datapoint` model, UUID v7 generator.

## Tests

- Unit tests spin up a JDK `com.sun.net.httpserver.HttpServer` on a random loopback port and
  point a real `DatahubClient` at it — no mocking framework. Follow that pattern for new
  service tests. Run with `./gradlew :datahub-java-sdk:test`.
- `SubscriptionListenIT` is end-to-end (ingest → Pulsar fan-out → subscription delivery) and
  needs a running backend; it is gated behind `RUN_LISTEN_TESTS=1` and configured through
  `BASE_URL`/`TOKEN` like `fromEnv()`.

## Consumers to keep in mind

- `datahub-analysis` calls the api through this SDK **as the calling user**: its
  `AnalysisApiClientFactory` builds a per-request `DatahubClient` with the caller's forwarded
  JWT as a static token (the SDK bakes the token into the client, hence per-request wrappers).
  Changes to `DatahubConfig`, `TokenProvider`, or service signatures ripple there.
- Out-of-tree consumers install the artifact with `publishToMavenLocal`; there is no
  public Maven release yet. `publish` targets whatever `-PmavenPublishUrl` names, and has
  no default. The version comes from the `javaSdkVersion` Gradle property
  (default `0.1.0-SNAPSHOT`).
