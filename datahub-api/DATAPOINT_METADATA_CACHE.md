# TODO: Valkey-cached timeseries metadata for datapoint ingestion

**Status:** proposed (not yet implemented)
**Module:** `datahub-api`
**Owner:** _unassigned_

## Why

Datapoint ingestion is a hot path, but `TimeseriesService.insertDatapoints()`
(`datahub-api/.../api/services/TimeseriesService.java`, ~line 728) and
`deleteDatapoints()` (~line 1051) both resolve the target timeseries from
PostgreSQL on every request via `timeseriesRepository.findByIdOrExternalId(...)`.

Two costs follow from that:

1. The methods are `@Transactional`, and because the API's `DataSource` is a
   `StatelessRoutingDataSource` handing out **unpooled** `SimpleDriverDataSource`
   connections (one fresh physical connect+auth per acquisition, eagerly acquired
   at transaction begin), every ingestion request opens a per-tenant Postgres
   connection purely to read small, slow-changing metadata.
2. `insertDatapoints()` holds that connection open across a loop of **synchronous,
   blocking** `allDatapointProducer.send(...)` calls — DB connection tied up across
   Pulsar I/O.

There is **no dual-write concern** here (the methods perform no Postgres writes,
so there is nothing to roll back, and the inline send is correct — this is
deliberately *not* routed through `AfterCommitMessagePublisher`). The only thing
keeping Postgres on the hot path is the metadata read.

## What the hot path actually needs from the entity

From `insertDatapoints` + `addData(...)` + the ACL check, the only fields read are:

| Field | Used for |
|-------|----------|
| `id` (long) | `DataCollectionString.setId`, producer topic resolution |
| `externalId` (String) | `addData`, `addToLatestValuesCache` |
| `valueType` id (`BIGINT`/`DECIMAL`/`NUMERIC`/`TEXT`) | per-value parse/validation |
| `valueType` name | `DataCollectionString.setValueType` |
| `dataSet` id (nullable) | `dataSecurity.assertCanWrite(ts)` |

That is the entire cacheable payload — small and stable.

## Plan

1. **`TimeseriesMeta` record** — immutable `(id, externalId, valueTypeId,
   valueTypeName, dataSetId)`. Place in `datahub-commons`.

2. **Extend `ValkeyService`** (`datahub-infra/.../services/ValkeyService.java`),
   mirroring the existing `fetchLatestDatapoint`/`setLatestDatapoint`/`delete(key)`
   pattern (it already has `RedisClient` + `jsonMapper` + `DEFAULT_EXPIRE_TIME = 300`):
   - `Optional<TimeseriesMeta> fetchTimeseriesMeta(String tenantId, String lookupKey)`
   - `setTimeseriesMeta(String tenantId, TimeseriesMeta meta)` — writes **both**
     lookup keys: `tsmeta:{tenant}:id:{id}` and `tsmeta:{tenant}:eid:{externalId}`,
     each with a TTL.
   - `evictTimeseriesMeta(String tenantId, long id, String externalId)` — deletes
     both keys.

3. **Rework resolution** in `insertDatapoints` / `deleteDatapoints`:
   cache lookup → on miss, a single `findByIdOrExternalId` load → populate cache.
   Replace `dataSecurity.assertCanWrite(ts)` with the existing
   `dataSecurity.assertCanWriteDataSet(meta.dataSetId())`. Once resolution is
   cache-backed, **drop `@Transactional`** — a cache hit touches no Postgres, and a
   miss does a single short read.

4. **Invalidation — must be airtight** (stale dataset/externalId gates a *write*
   permission check, so this is security-sensitive). Evict in every metadata-mutating
   path:
   - `updateTimeseries(...)` (~line 1198) — externalId and dataset can change →
     evict **old and new** externalId.
   - `deleteTimeseries(...)` (~line 206) — evict, or a deleted series stays
     "writable" in cache until TTL.
   - `save(...)` (~line 521) — evict-by-externalId on create to clear any stale
     entry from a prior delete+recreate of the same externalId.
   - Eviction propagates across API instances because Valkey is shared — **but only
     if every writer calls it.** Audit for any other path that mutates a timeseries'
     dataset or externalId.

## Gotchas to bake in

- **Tenant scoping is mandatory.** Keys must include `TenantContext.getTenantId()`
  (ids and externalIds are per-tenant). The existing latest-datapoint cache already
  does this — `ValkeyService.latestDatapointKey` hashes the externalId together with the
  tenant id — so follow that pattern rather than inventing a second convention.
- **Dual-key lookup.** Requests resolve by id *or* externalId; write both keys so
  either hits, and evict both.
- **TTL bounds a write-ACL staleness window.** With explicit eviction on every write
  path, TTL is only a backstop for a missed evict — keep it modest (~300s) since it
  gates a permission check.
- **Confirm `valueType` immutability** post-create. If it cannot change, that field
  is safe to cache for the full TTL without extra invalidation.
- **Skip negative caching** of "not found" (the `ts == null` → 404 branch) to avoid a
  tombstone/eviction dance, unless profiling shows it's needed.
- **Stampede:** an uncached hot series under load yields concurrent misses each doing
  a DB load — acceptable; add single-flight only if measured.

## Out of scope / not changing

- `ResourceService` create/update/delete and `TimeseriesService` save/update/delete
  of *metadata* keep their `@Transactional` + `AfterCommitMessagePublisher` pattern —
  those do real Postgres writes (and `delete` paths hold a pessimistic
  `lockByIdIn`), so the after-commit publish is load-bearing there.
- This is purely the datapoint **value** ingestion path.
