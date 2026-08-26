# Remove `TimeseriesEntity.securityCategories`

Status: **proposed** (2026-08-26). Deferred deliberately — this is a wire-and-schema change, and
the field is inert, so there is no urgency and no reason to fold it into unrelated work.

## Why

`securityCategories` is stored, writable, and returned, and **nothing reads it**. It is a
Cognite-shaped field the platform never wired up:

- **It is not access control.** Nothing in `datahub-api/.../datasecurity/` references it. Dataset
  ACLs are Keycloak organization groups (`DATASET_ACL_SETUP.md`); this field plays no part in
  who may read a series, despite the name.
- **No client consumes it.** The console's timeseries form only ever sets it to `undefined`
  (`right-form-content/timeseries/form.js:555`); the Java SDK never mentions it; the MCP lean DTO
  deliberately drops it (`LeanTimeseries`); the Neo4j consumer ignores it.
- **It is not in the public docs.** `datahub-sdk-docs/docs/reference/timeseries.md` does not
  document it. The only mention anywhere is a note saying graph reads *omit* it.

So it is write-only state, and it is not free.

## What it costs while it stays

- **Six `@EntityGraph` overrides** in `TimeseriesRepository` (lines 28–56) exist *only* for this
  field, with a comment explaining that Spring Data's default `FETCH` graph would otherwise force
  it LAZY and throw `LazyInitializationException` at serialization time. Delete the field and all
  six overrides and the explanation go with it.
- **An N+1 on every generic node read.** It is an `@ElementCollection(fetch = EAGER)`, so
  Hibernate loads it whenever a `TimeseriesEntity` is materialised — including from
  `/resources/filter`, which spans every node type. A collection cannot be join-fetched without
  multiplying parent rows, which would break `setMaxResults`, so paging rules that fix out. The
  interim mitigation is a `@BatchSize` on the collection; removing the field removes the problem
  instead of batching it.
- **A join table** (`timeseries_security_categories`, from `V1__create_tables.sql`, re-keyed in
  `V14`) that no query filters on.
- Two `SEC_CAT_REF` constants in `NodeRepoImpl` and `TimeseriesCustomRepoImpl`.

## Scope

**In:** the timeseries field only.

**Out:** `INode.securityCategories` / `inodes_security_categories` (files). It is a separate
field on a separate entity with its own lifecycle — `TrashPurger` deletes its rows — and files
have their own access story. Whether it is equally inert is a separate question; do not assume
this task answers it.

## What removal touches

| Where | What |
|---|---|
| `datahub-infra/.../jpa/domains/TimeseriesEntity.java` | the field, its `@ElementCollection`/`@CollectionTable`/`@BatchSize`, and its `toString` part |
| `datahub-infra/.../repositories/node/TimeseriesRepository.java` | six `@EntityGraph` overrides + the comment justifying them |
| `datahub-infra/.../repositories/node/NodeRepoImpl.java`, `TimeseriesCustomRepoImpl.java` | the `SEC_CAT_REF` constants and their uses |
| `datahub-infra/.../transformers/TimeseriesTransformer.java` | the copy into the DTO |
| `datahub-api-model/.../timeseries/Timeseries.java` | the DTO field and its example in the class javadoc |
| `datahub-api-model/.../timeseries/TimeseriesFields.java` | the `UpdateNumberListField` |
| `datahub-api/.../services/TimeseriesService.java` | the set/add/remove block in the update path (~1349) |
| `datahub-api/.../controllers/ResourceController.java` | two javadoc notes naming it as omitted from graph reads |
| `datahub-api/.../mcp/dto/LeanTimeseries.java` | javadoc naming it among the dropped fields |
| `datahub-api-model` tests | `TimeseriesWireContractTest` asserts the value and the exact key set |
| `datahub-api` tests | `McpDtoTest` asserts it is absent — that assertion becomes vacuous |
| new Flyway migration | `DROP TABLE timeseries_security_categories` |

## The one real constraint: it is on the Pulsar wire

`TimeseriesFields` sits inside `UpdateTimeseries`, which is a field of `ResourceCudMessage`
(`updateTimeseries`), serialized with Avro reflection. Removing it changes the message schema.

The consumers do not read it, which makes this safe but not free: during a rolling deploy an old
reader can meet a new writer. Sequence it like the other Avro changes in this repo — deploy the
consumers first on a build that tolerates the field's absence, then the api — and state the
ordering in the release notes. Do not bundle it with another schema change.

## Adding it back, properly

Nothing depends on it, which is exactly why re-adding it later is cheap. "Properly" means
deciding first what it is *for*: if the intent is per-series access control, it has to be resolved
in `DataSecurity` alongside the dataset grants and expanded the way dataset ACLs are, not merely
stored. Reintroduce it with that enforcement in the same change, so it cannot drift back into
being write-only state.

## Verification

- `./gradlew build` green, with `TimeseriesWireContractTest`'s key set updated to match.
- A timeseries create/read/update round trip through `/timeseries/*` and `/resources/*` shows the
  field gone from every response and rejected (400, unknown field) on input — the api reads
  request bodies strictly, so a client still sending it learns immediately rather than silently.
- `docs-check`: `datahub-sdk-docs` for the removed request/response field.
- Confirm the generic node read no longer issues a per-row collection query.
