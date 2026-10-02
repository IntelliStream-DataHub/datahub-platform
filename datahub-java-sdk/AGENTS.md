# datahub-java-sdk

Thin, synchronous Java client for the DataHub Platform REST API, published as
`ai.intellistream:datahub-sdk` for out-of-tree consumers. Usage docs: [README.md](README.md).

## Hard constraints

- **No server stack.** Built on the JDK `java.net.http.HttpClient`; depends only on
  `datahub-api-model` (the wire contract) plus Jackson 3, zstd-jni and slf4j-api. No Spring, no Feign, no
  Vault client — the Spring Boot plugins in `build.gradle` exist only for BOM version management.
  Keep the dependency surface at zero-/tiny-transitive jars; this artifact ships to external users.
  zstd-jni is the one native library, and it is there because the binary datapoint path requires
  zstd and the JDK has none. The Arrow frames come from api-model's own codec on the FlatBuffers
  classes, not from arrow-java: no allocator, no `--add-opens`.
- **Binary ingest is its own method.** `ingestBinary(...)` and `binaryBuffer()` on
  `TimeseriesService` go to `POST /timeseries/data/binary`; the JSON `ingest(...)` is untouched
  and the durable spool applies to it only. The binary path resolves series through
  `/timeseries/byids` (`client/SeriesResolver`), so it needs read access to the dataset too.
- **Branch on the problem `type`, never on a substring of the body.** The api answers every
  failure with one RFC 9457 shape whose `type` URI is the contract; `detail` and `title` are prose
  for a human and may be reworded. `Problem.of(status, body)` never throws and never returns null,
  so an error path has no special case for a proxy's HTML or an empty 502 — `slug()` is simply null
  there and the status decides. A substring search over the whole body also fires on a `detail`
  sentence that merely mentions the thing, which is how `unknown-timeseries` used to be matched.
- **`retry` decides what is sent again; the status decides what is spooled.** They answer different
  questions and neither implies the other (`IngestResult.isRetryable` vs `isBufferable`). An expired
  token is `change-request` — that request will never work as it stands — yet holding the data while
  someone renews the credential is exactly what the spool is for. Going by `retry` in `isBufferable`
  would stop buffering 401/403 and defeat the feature.
- **Log through SLF4J, never `System.err`/`System.out`.** The SDK runs inside someone else's
  application, and its warnings belong in that application's logging backend.
- **Wire types come from `datahub-api-model`** — never redefine request/response DTOs here.
  In-tree it is a project dependency (`api project(':datahub-api-model')`); the published POM
  pins resolved versions so non-Spring consumers work.
- **Events ingested without an id get a UUID v7** (`util/UuidV7`) stamped client-side before the
  first send, so a retry carries the same id and collapses in ClickHouse
  (`ReplacingMergeTree ORDER BY id`). Never switch to random v4 ids for events — they scatter
  the sort key and degrade insert/merge/query performance.
- **The durable spool must stay memory-safe** (`client/DurableSpool`): append to a plain NDJSON
  active segment, gzip-seal at ~50 MiB rollover, stream sealed segments in fixed-size chunks on
  flush — a multi-gigabyte spool never loads into memory. Buffer only retryable failures:
  unreachable (network error, 429, 5xx) and auth (401/403). Terminal errors such as 400 are
  surfaced, never buffered. The one 403 that is **not** buffered is a tenant that has reached a
  permanent ceiling (`type` ends `/errors/tenant-limit-reached`): replaying it can never succeed,
  so buffering would fill the spool with refused data and bury the message saying the limit is
  raised by asking. Bounded by `bufferRetention` (time) and `bufferMaxBytes` (size); off by default.

## Layout (`ai.intellistream.datahub.sdk`)

**`DatahubClient` is the only way in.** Everything a caller does is reached through it. The
plumbing is package-private, and so are the service constructors, which is why the services and
the plumbing share one package: Java can only hide a constructor from other packages. Keep new
plumbing package-private in `client/`, and keep new service constructors package-private.
Narrowing a published public type later is a breaking change. The other packages hold only
value types a caller names.

- `client/` — the entry point and everything behind it:
  - public: `DatahubClient` (one accessor per service); `DatahubConfig` (builder; `fromEnv()`
    on `BASE_URL` + `TOKEN` or `CLIENT_ID`/`CLIENT_SECRET`/`TOKEN_URI`, optionally
    `SCOPE`/`AUDIENCE` and the `ASSERTION*` keys that select the `jwt-bearer` grant; Vault
    variants via `VaultSecretLoader`, a JDK-HttpClient KV v2 read supporting token and AppRole
    auth); one `*Service` per API area (resources, assets, functions, timeseries, datasets,
    events, labels, policies, governance, tenant, units, files, subscriptions); and the handles
    a service returns, `BinaryIngestBuffer` and `SubscriptionListener`. `assets` and `functions`
    are the typed views of the `ASSET`/`FUNCTION` corners of the same graph `resources` serves
    polymorphically; `labels` reads and writes through `LabelForm`, which is
    `@Schema(name = "Label")` and is the label wire shape on both sides.
  - package-private: `ApiHttp` (request helpers; every non-2xx becomes a
    `DatahubApiException`); `TokenProvider` (static token pass-through, or a cached single-flight
    exchange refreshed ~30 s before expiry, either client-credentials or the RFC 7523
    `jwt-bearer` grant when an assertion source is configured; the assertion is re-requested per
    exchange, never cached, because providers commonly reject a replayed one); the ingest
    machinery (`DatapointIngestor`, `EventIngestor`, `BatchExecutor`, `DurableSpool`,
    `DatapointSpool`, and for the binary path `BinaryDatapointIngestor` and `SeriesResolver`).
- `http/` — `DatahubApiException`. Every refusal is read as the api's RFC 9457 problem
  document through `problem()` (`ai.intellistream.datahub.api.errors.Problem`, in api-model).
- `ingest/` — `IngestOptions`, `BinaryIngestOptions`, `IngestResult`.
- `subscriptions/` — `SubscriptionMessage`, `SubscriptionError`, delivered by
  `client/SubscriptionListener` (durable subscription listening over the api's WebSocket endpoint
  with per-subscription ack/nack).
- `timeseries/`, `util/` — `Datapoint` model, UUID v7 generator.

## Tests

- Unit tests spin up a JDK `com.sun.net.httpserver.HttpServer` on a random loopback port and
  point a real `DatahubClient` at it — no mocking framework. Follow that pattern for new
  service tests. Run with `./gradlew :datahub-java-sdk:test`.
- **`SdkWireContractTest` is the table every call belongs in.** One row per endpoint, asserting
  the verb and path against what the controller maps, and re-binding the request body into the
  controller's `@RequestBody` type with `FAIL_ON_UNKNOWN_PROPERTIES` on, which is what
  `StrictRequestBodyConfig` does server-side. Add a row when you add a call: the older tests
  assert on responses, so they pass a call that could only ever have returned a 400 (which is how
  `units().byIds` and `events().byIds` shipped unusable).
- **A `204` endpoint is typed `void`.** `ApiHttp` returns null for a 204, so a declared wrapper is
  a null a caller dereferences. `NoContentResponseTest` stubs the real 204 and asserts the return
  type for all nine.
- `SubscriptionListenIT` is end-to-end (ingest → Pulsar fan-out → subscription delivery) and
  needs a running backend; it is gated behind `RUN_LISTEN_TESTS=1` and configured through
  `BASE_URL`/`TOKEN` like `fromEnv()`.

## Consumers to keep in mind

- `datahub-analysis` calls the api through this SDK **as the calling user**: its
  `AnalysisApiClientFactory` builds a per-request `DatahubClient` with the caller's forwarded
  JWT as a static token (the SDK bakes the token into the client, hence per-request wrappers).
  Changes to `DatahubConfig`, `TokenProvider`, or service signatures ripple there.
- Released versions are on Maven Central from 1.0.0; out-of-tree consumers of an unreleased
  build install it with `publishToMavenLocal`. The remote `publish` repository exists only when
  `-PmavenPublishUrl` names one; `centralBundle` (root project) stages to a local directory
  for Maven Central. The version is the platform's, the `version` in the root
  `gradle.properties`, and the Maven artifactId is `datahub-sdk`, not the Gradle project name.
