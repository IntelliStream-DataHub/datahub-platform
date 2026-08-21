# DataHub Java SDK

A thin, synchronous Java client for the **DataHub Platform** REST API. Built on the JDK
`java.net.http.HttpClient` (no Feign) and Jackson 3, it reuses the platform's own
wire-contract types from `ai.intellistream:datahub-api-model`.

The SDK covers resources, timeseries (including datapoint ingestion), events, datasets,
units, files, and subscriptions (including WebSocket listen with per-subscription ack/nack).

## Requirements

- **Java 25+** (the SDK and `datahub-api-model` target Java 25).

## Quick start

```java
// Configure from the environment (BASE_URL + TOKEN, or CLIENT_ID/CLIENT_SECRET/TOKEN_URI)
DatahubClient client = DatahubClient.fromEnv();

// …or explicitly
DatahubClient client = DatahubClient.create(
    DatahubConfig.builder()
        .baseUrl("https://api.intellistream.ai")
        .clientCredentials("my-service", secret,
                "https://keycloak.intellistream.ai/realms/datahub/protocol/openid-connect/token")
        .build());

DataWrapper<Resource> resources = client.resources().getById(5677892L);
resources.getItems().forEach(System.out::println);
```

### Authentication

Either a static bearer `TOKEN`, or the client-credentials triple `CLIENT_ID` + `CLIENT_SECRET` +
`TOKEN_URI`. Two further parameters are sent with the token request only when set:

| Key | Builder | When you need it |
|-----|---------|------------------|
| `SCOPE` | `.scope(...)` | `organization:*` when the DataHub realm uses Keycloak Organizations (see below). Space-separate several. Entra ID instead wants `api://<app-id-uri>/.default`. |
| `AUDIENCE` | `.audience(...)` | Auth0 requires it. Keycloak ignores it. |

> **Whether you need `SCOPE` depends on the realm.** DataHub resolves your tenant from the
> `organization` claim, and there are two ways a realm produces it:
>
> - **Keycloak Organizations** — the claim comes from a dynamic client scope, so the request must
>   name it: `SCOPE=organization:*`, or `organization:<alias>` to pin one tenant. Without it the
>   token carries no tenant and every call fails `401 invalid_token`, which looks like bad
>   credentials but is not.
> - **A protocol mapper on the client** — emitted unconditionally, no `SCOPE` needed.
>
> If calls fail `401 invalid_token` with credentials you believe are correct, this is the first
> thing to check.

Setting an assertion source switches the request at `TOKEN_URI` to the RFC 7523 `jwt-bearer`
grant, exchanging a JWT from one issuer for a token from another — how an Entra ID service
principal reaches a Keycloak-backed API. `CLIENT_ID`/`CLIENT_SECRET`/`TOKEN_URI` then describe the
client performing the exchange:

| Key | Builder | Meaning |
|-----|---------|---------|
| `ASSERTION` | `.assertion(...)` | A ready-made JWT. Never refreshed — prefer the credentials below. |
| `ASSERTION_CLIENT_ID` / `ASSERTION_CLIENT_SECRET` / `ASSERTION_TOKEN_URI` | `.assertionCredentials(...)` | Fetch the assertion from another provider (all three). |
| `ASSERTION_SCOPE` / `ASSERTION_AUDIENCE` | `.assertionScope(...)` / `.assertionAudience(...)` | Narrow the assertion request. |

```java
DatahubConfig.builder()
    .baseUrl("https://api.intellistream.ai")
    .clientCredentials(clientId, secret, "https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token")
    .scope("api://datahub/.default")
    .build();
```

Against Keycloak neither is needed — see [GETTING_STARTED.md](../GETTING_STARTED.md#machine-to-machine-tokens).
Note that a token minted straight from Entra is **not** accepted by `datahub-api`, which trusts a
single Keycloak issuer; see [EntraID.md](../EntraID.md) for the bridge.

## Filtering

Four endpoints answer structured queries — `datasets().filter(...)`, `resources().filter(...)`,
`timeseries().filter(...)` and `events().filter(...)`. Each takes either a criteria object or the
retriever that wraps it with `limit`, `sort` and `cursor`.

```java
TimeseriesFilter criteria = new TimeseriesFilter();
criteria.setDataSetId(List.of(IdCollection.createFromExternalId("plant_a")));
criteria.setName(List.of("Pump*", "Valve*"));        // wildcards, OR-ed
criteria.setUnit(List.of("kg/hr", "deg_*"));
criteria.setMetadata(Map.of("owner", "plant-a", "health", null));

client.timeseries().filter(criteria).getItems().forEach(System.out::println);
```

The rules are the same across all four:

- **Criteria AND together, list entries OR.** `name: ["Pump%", "Valve%"]` means "named like
  either"; adding `source: ["sap"]` means "named like either **and** from sap".
- **The OR'd fields are named in the singular but take lists.** Each accepts a bare value or an
  array, so `name: "Pump 1"` and `name: ["Pump 1", "Pump 2"]` are both valid — the singular is what
  reads correctly in the common case of asking for one thing.
- **`labels` and `metadata` are the exceptions** — every entry must be present. They keep their
  plural names for exactly that reason: a second entry narrows the query where a second `name`
  would widen it. A **null metadata value matches the key alone**, whatever it holds, which is what
  the removed `metadataKey`/`metadataValue` pair used to say.
- **Text lists take literals or wildcards in the same list.** `*` and `%` are wildcards, `_` is
  literal (external ids are built out of underscores), and matching is case-insensitive — except
  literal event external ids, which are case-sensitive because events hash them verbatim.
- **An empty list places no restriction**, same as omitting it. The single exception is
  `dataSetId`, where omitted means "no data set restriction" and an explicit `[]` means "narrow to
  no data sets".
- **`dataSetId` takes ids or externalIds** and expands down the `BELONGS_TO` hierarchy, so naming
  a parent covers everything beneath it.

`resources().filter(...)` is the generic node query: assets, timeseries, functions, resources,
data sets and policies all live in one table, so it searches across every type at once. Narrow it
with `nodeType`, and read what came back off each node's type label. The other three each answer
for one type.

### Paging

A filter call returns at most `limit` rows (default 1000, max 10000) and, when more remain, a
`nextCursor` to send back as the next request's `cursor`. Absent means there are no further pages —
there is no separate end-of-data flag, so "keep going while `nextCursor` is present" is the whole
loop. The SDK passes both through rather than walking them for you:

```java
TimeseriesRetreiver request = new TimeseriesRetreiver();
request.setFilter(criteria);
request.setLimit(5000);                      // page size, not a total

String cursor = null;
do {
    request.setCursor(cursor);
    DataWrapper<Timeseries> page = client.timeseries().filter(request);
    page.getItems().forEach(this::process);
    cursor = page.getNextCursor();
} while (cursor != null);
```

Reuse the same retriever across pages, as above, rather than building a fresh one per page: the
cursor has to go back out with the same criteria and `sort` that produced it.

Order comes from the retriever's `sort` — one property plus the `id` tie-breaker the query appends,
defaulting to newest created first (event time ascending for events). The property is a whitelist,
and **an unrecognised one silently falls back to the default** rather than failing, so a typo
returns a well-formed page in the wrong order:

| | Sortable properties |
|-|-|
| datasets, resources, timeseries | `id`, `externalId`, `name`, `source`, `description`, `createdTime`, `lastUpdatedTime`, `dataSetId` |
| events | `eventTime`, `createdTime`, `lastUpdatedTime`, `externalId`, `type`, `subType`, `status`, `source`, `dataSetId` |

A cursor is a position in **one particular order**, so it has to travel with the `sort` that
produced it — send it back under a different one and the API rejects it rather than answering with
a silently incomplete page. Reusing one retriever across the walk, as above, is what keeps the two
together.

## Durable ingest buffering

Optionally, the client can buffer datapoint and event ingestion to disk when a send can't get
through, and flush it automatically on the next ingest call once it can. Two kinds of failure
buffer: the API being **unreachable** (network error, HTTP 429 or 5xx), and an **auth failure**
(HTTP 401/403, e.g. an expired token) — so data keeps accumulating until either connectivity or
the credential is restored, then flushes. A terminal error such as HTTP 400 is surfaced, not
buffered. It is **off by default**; enable it on the config:

```java
DatahubClient client = DatahubClient.create(
    DatahubConfig.builder()
        .baseUrl("https://api.intellistream.ai")
        .token(token)
        .enableBuffering()                          // defaults: 72h window, 5 GiB cap
        // or set either bound explicitly (each opt-in enables buffering):
        // .bufferRetention(Duration.ofMinutes(60))
        // .bufferMaxBytes(2L * 1024 * 1024 * 1024)
        // .bufferDirectory(Path.of("datahub-spool"))   // default: .datahub-spool
        .build());

IngestResult r = client.timeseries().ingest(byExternalId);
if (r.buffered() > 0) {
    // couldn't send (server unreachable or auth failure): r.buffered() datapoints are
    // spooled, will retry next call
}
```

The spool is a **segmented, gzip-compressed, newline-delimited-JSON log** (one subdirectory
per stream: `datapoints/`, `events/`). It is bounded on two axes, either of which may be left
unset:

- **time** (`bufferRetention`): records older than the window are dropped.
- **size** (`bufferMaxBytes`): when the on-disk total exceeds the cap, the oldest segment is
  dropped.

It is memory-safe: failures are appended to a plain active segment, that segment is gzip-sealed
at a ~50 MB rollover, and on flush segments are streamed and sent in fixed-size chunks, so even a
multi-gigabyte spool never loads into memory. A torn trailing line from an unclean shutdown is
skipped on read, and a spool left on disk is recovered on the next client start.

### Idempotent retries

Retries are safe because the backend collapses duplicates on the storage sort key:

- **Datapoints** are keyed by `(series external id, timestamp)`, so re-sending an identical
  datapoint is a no-op.
- **Events** are keyed by `id`, but the server would otherwise mint a fresh id per request, so a
  retry would look like a new event. To prevent that, `events().ingest(...)` stamps each event that
  has no `id` with a **time-ordered UUID v7** before the first send, and the spool keeps that id, so
  a retry carries the same id and collapses.

If you set the event `id` yourself, **use a time-ordered UUID (v7), not a random one.** The events
table is `ReplacingMergeTree ORDER BY id`, so a v7's leading timestamp keeps inserts appending in
sort order (sequential writes, tight primary index, good cache locality and part pruning). A random
v4 id scatters writes across the keyspace and can badly degrade insert/merge/query performance.
`ai.intellistream.datahub.sdk.util.UuidV7` generates compatible ids.

## Building

```bash
./gradlew build
```

The SDK builds as part of the platform Gradle build and depends on `datahub-api-model` as a
sibling project.

There is no public Maven release yet, so an out-of-tree project gets the SDK by building it
here and installing both artifacts to the local Maven repository:

```bash
./gradlew :datahub-api-model:publishToMavenLocal :datahub-java-sdk:publishToMavenLocal
```

Then add `mavenLocal()` to that project's repositories and depend on
`ai.intellistream:datahub-sdk:0.1.0-SNAPSHOT` (or whatever `javaSdkVersion` you built with).

To publish to a Maven repository of your own, pass its URL; there is deliberately no default:

```bash
./gradlew :datahub-api-model:publish :datahub-java-sdk:publish \
    -PmavenPublishUrl=https://example.org/api/packages/<owner>/maven \
    -PmavenPublishUser=<user> -PmavenPublishToken=<token>
```
