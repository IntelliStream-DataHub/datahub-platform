# Scaling the datapoint ingestion path

How far the time-series ingestion path scales, where it stops, and what to change when it
does. The path is: HTTP API, Apache Pulsar, the stateless consumer, ClickHouse.

This is an engineering analysis, not a benchmark report. Numbers described as measured are
measured; everything else is reasoning from the code and from the shape of the systems
involved, and is marked as such. Where a claim needs a load test to settle, it says so.

## Summary

A single Pulsar cluster is not the first constraint, so "one cluster or many" is the wrong
first question.

The order in which limits bite:

1. **The API request path.** Per-collection database and cache round trips inside a
   transaction, plus a synchronous Pulsar send. Whether one API instance can absorb a burst
   depends on the shape of the request, specifically how many datapoints arrive per
   collection, far more than on anything downstream. This is the limit the binary ingest
   path removes, and the measurement is below: 46 times less API CPU for the same points,
   because the client does the parsing.
2. **ClickHouse.** The sustained-write ceiling. Pulsar can buffer a burst; ClickHouse has to
   absorb the rate continuously, and the namespace backlog policy means it cannot fall behind
   for long.
3. **Pulsar.** Currently the most headroom of the three. The datapoint topic is partitioned
   and the producer is tuned to absorb bursts rather than shed them.
4. **Multiple clusters.** Only relevant after the above, and only for the very high rate
   class.

## Getting the unit right

"Billions of datapoints per second" is ambiguous in a way that changes the answer by about
1000x, because **one Pulsar message is not one datapoint**. `TimeseriesService` packs every
datapoint in an API request into a single `DataWrapperBin` and sends it as one message, so
message rate is datapoint rate divided by the batch factor.

| Reading of the requirement | Messages/s at 1,000 dp/msg | Feasibility |
|---|---|---|
| 1B **datapoints**/s | 1,000,000 msg/s | Extreme but conceivable on a large tuned cluster |
| 1B datapoints/s at 10,000 dp/msg | 100,000 msg/s | Comfortable for one cluster |
| 1B **messages**/s (1 dp/msg) | 1,000,000,000 msg/s | Not possible on any cluster |

For scale, one of the largest streaming deployments in the industry runs on the order of tens
of millions of messages per second across its entire multi-cluster fleet. As a message rate,
"billions per second" is off the map. As a datapoint rate behind fat batched messages it is
hard but real.

### The part that cannot be tuned away

Even with perfect batching, byte rate is a hard floor. A datapoint (id, value, timestamp,
type) is roughly 30 to 50 bytes on the wire:

- 1B dp/s at ~40 B is about **40 GB/s** of raw ingress, roughly 320 Gbps
- Pulsar persists through BookKeeper with a write quorum of 2 to 3, so internally closer to
  **1 Tbps**
- ZSTD, already enabled, buys perhaps 3 to 5x, leaving **~10 GB/s** sustained to durable
  replicated storage

That is a data-center-scale commitment, not a configuration change. Confirm the requirement is
real before designing for it: peak against sustained, and "per second" against "per day",
routinely differ by four or more orders of magnitude.

## The path as built

```
HTTP API ──> allDatapointProducer ──> [ persistent://internal/datapoints/all-datapoints ]
                                                   │  16 partitions, spread across brokers
                                                   ▼
                                   BatchedDatapointsListener (Shared subscription)
                                                   │
                        ┌──────────────────────────┴──────────────────────────┐
                        ▼                                                     ▼
             ClickHouse bulk insert                        fanoutProducer ──> [ subscriptions/fanout ]
             (one pooled Client per tenant)                    8 partitions, broker-side entry filter
                                                                              │
                                                                              ▼
                                                                    WebSocket subscribers
```

### Configuration reference

File and class names, not line numbers, because line numbers rot.

| Component | Setting | Value | Source |
|---|---|---|---|
| Pulsar | version | 4.0.11 | `gradle.properties` |
| Pulsar client | `memoryLimit` | 512 MB (`datahub.pulsar.memory-limit-mb`) | `PulsarConfig` |
| Pulsar client | `ioThreads` / `listenerThreads` | 8 / 8 | `PulsarConfig` |
| Pulsar client / admin | service URLs | one `serviceUrl`, one `serviceHttpUrl` | `PulsarConfig` |
| `all-datapoints` | partitions | 16, provisioned before the producer connects | `PulsarProducerConfig`, `PartitionedTopicProvisioner` |
| `all-datapoints` producer | batching delay | 10 ms (`datahub.pulsar.datapoints.batching-max-publish-delay-ms`) | `PulsarProducerConfig` |
| `all-datapoints` producer | `maxPendingMessages` | 5,000 / 50,000 across partitions | `PulsarProducerConfig` |
| `all-datapoints` producer | `blockIfQueueFull` | configurable, **default false** (`datahub.pulsar.datapoints.block-if-queue-full`) | `PulsarProducerConfig` |
| `all-datapoints` producer | compression / send timeout | ZSTD / 6 s | `PulsarProducerConfig` |
| Message granularity | datapoints per message | one API request, one message | `TimeseriesService` |
| Datapoint consumer | subscription type | Shared | `BatchedDatapointsListener` |
| Datapoint consumer | batch receive | 20 MB or 500 ms | `BatchedDatapointsListener` |
| Datapoint consumer | workers / DLQ / ack timeout | 8 / 10 redeliveries / 120 s | `BatchedDatapointsListener` |
| Datapoint consumer | partition discovery | 30 s, so partition-aware already | `BatchedDatapointsListener` |
| Datapoints namespace (dev) | backlog quota | 2 GB, `producer_exception` | `InitNamespaces` |
| Datapoints namespace (prod) | backlog quota | provisioned manually | `datahub-api/PULSAR_SETUP.md` |
| Fanout topic | partitions / routing | 8 / `KEY_BASED`, keyed by subscription externalId | `SubscriptionTopicProvisioner` |
| Events consumer | subscription / workers | `Key_Shared` / 4, bounded queue 1024 | `BatchedEventsListener` |
| ClickHouse | client lifecycle | one long-lived `Client` per tenant, rebuilt on config change, closed on shutdown | `ClickHouseClientPool` |
| ClickHouse | insert settings | `async_insert=1`, `max_threads=8`, ZSTD RowBinary stream, 30 s timeout | `ClickHouseService` |

An earlier design created one Pulsar topic per timeseries. That is a scalability anti-pattern,
because per-topic metadata and load-balancing cost grow with an unbounded topic count. It was
removed; datapoints now flow through the shared partitioned topic.

## The binary ingest path, measured

`POST /timeseries/data/binary` exists to move this document's first limit, the api parsing every
value, off the api and onto the client. The client resolves each series once, checks and sorts the
values, writes them as Arrow IPC frames in the schema the ClickHouse table wants, compresses each
frame with zstd, and posts them; the api validates a frame and forwards its bytes. Arrow IPC
rather than ClickHouse Native because the two parse at the same speed on these tables once frames
hold a thousand points, so the tie went to the format that also suits reads and that any
Arrow-capable tool can produce. `FrameLimits` and `ArrowSchemaCanon` in `datahub-api-model` are
the normative caps and schemas; the byte-level spec belongs in the SDK documentation.

Measured 2026-09-12 on one 32-core host running the clients, both services, Pulsar and ClickHouse
together. Re-run with `./gradlew :datahub-e2e:benchmark`; `datahub-e2e/README.md` has the setup
and the two product limits that must be off. **Everything must be built optimised.** `bootRun`
disables C2, `cargo test` defaults to `opt-level = 0`, and a debug PyO3 module is equally
useless. A first run of this comparison reported Rust as slower than Java purely from that.

### What it buys, 100 million points

| | JSON | binary | |
|---|---|---|---|
| **api CPU** | **287.8 s** | **6.3 s** | **46x** |
| Consumer CPU | 64.2 s | 6.4 s | 10x |
| **Bytes on the wire** | **5.00 GB** | **346 MB** | **14.4x** |
| Ingest wall time | 52.0 s | 30.4 s | 1.7x |
| Latency p99 per million-point call | 618 ms | 345 ms | |
| api peak resident | 3.8 GB | 5.2 GB | costs more |
| Client heap growth | 1.39 GB | 1.91 GB | costs more |

The api CPU is the result. Accepting a hundred million points cost the JSON path 288 seconds of a
core and the binary path 6, because one parses every value and the other checks a frame and
forwards bytes. The costs land where the design put them: more client heap, and a larger resident
set on the api because a request body is held while every frame in it is validated.

### Scaling out, and the sustained rate

Concurrent Rust clients, 25 series each, 1M points per request. The backlog column separates a
rate the pipeline holds from one it is only absorbing.

| Clients | JSON | binary | Binary is | `datapoint-blocks` backlog peak |
|---|---|---|---|---|
| 1 | 720,022/s | 3,839,090/s | 5.3x | not sampled |
| 4 | 1,588,183/s | **11,318,630/s** | 7.1x | under 100 |
| 8 | 1,756,275/s | 14,558,392/s | 8.3x | 1,626, cleared in ~7 s |
| 12 | not measured | ~17,700,000/s | | 4,080 to 4,778 |

- **Four clients is the sustained point.** A billion points at 11.3M/s with the backlog never
  passing a hundred messages, ClickHouse absorbing as fast as the clients produced. A billion rows
  land in 2.72 GiB, 2.67 bytes each. This is the figure to quote.
- **Eight and twelve are not sustained.** They reach 14.6M and ~17.7M but the backlog grows, so
  they borrow from Pulsar's burst absorption; a long enough run hits the namespace quota.
- **JSON does not scale out.** It saturates near 1.75M/s: four clients give 2.2x one, eight give
  almost nothing more. Every client queues behind the same parsing, so adding them cannot help.
- **The host is the constraint, not the path.** Per-client throughput falls in proportion as
  clients are added, and when the clients stop ClickHouse drains at ~20M rows/s, so it was starved
  of CPU rather than insert-bound. Clients and ClickHouse on their own hardware have more headroom
  than this measures.

Two caveats on the ratios. This benchmark sends 10,000 to 40,000 points per collection, the
*favourable* shape for JSON, whose throughput depends heavily on that; a small-collection caller
gains more than the table shows, unmeasured. And the generated signal is a slow sine plus noise,
one per series. Giving every series identical values let zstd compress across them and reported
0.52 bytes per point against the honest 3.46.

### The three clients, 2M points over 10 series

| Client | JSON | binary | | in-call binary |
|---|---|---|---|---|
| Java | 1,504,579/s | 2,439,236/s | 1.6x | 2,941,000/s |
| Rust | 720,022/s | 3,839,090/s | 5.3x | 8,065,000/s |
| Python | 288,638/s | 758,542/s | 2.6x | 1,310,000/s |

In-call is the same points over the time inside the SDK call; the gap to wall clock is the client
generating its own data. Every client gains, most where most of its cost was building JSON.

Python is slowest for reasons at its edges rather than in the path. It runs the same Rust core as
the Rust SDK, so the frame building, compression and posting are identical code; what differs is
that it generates its points in interpreted code, its calls carry 50,000 points against the other
two clients' 500,000 so it pays ten times the per-call overhead, and every point crosses the PyO3
boundary one at a time, a Python `datetime` and a float per row turned into a timestamp and two
allocated strings. That conversion happens *inside* the call, which is why its in-call rate
trails Rust's sixfold on shared code. The fix is to pass arrays rather than objects, through
numpy or the Arrow PyCapsule interface; not implemented.

### Tried and not worth it

Each was implemented, both services rebuilt and the run repeated, not argued from theory.

| Change | Result | Verdict |
|---|---|---|
| Requests of 2M or 3.2M points instead of 1M | 3.99M and 3.86M against 3.82M points/s | Flat; already past per-request overhead |
| Raise the 3.2M request cap to 4M | 3.46M points/s, p99 six times worse | Worse. Reverted |
| Consumer merge to 4M rows, receive batch to 64 MiB | 10.6M against 11.0M baseline | No change. Reverted |
| `max_insert_threads` and `max_threads` to 12 | 16.9M and 18.2M raised, 18.1M and 17.7M not | Inside run-to-run spread. Reverted |
| Concurrent request posting in the Rust SDK | 3.81M against 3.63M, in-call 9.40M against 8.34M | Kept, 4 in flight, only matters above 3.2M points per call |

None of the thread or buffer settings moved the result, for the reason the scaling table shows:
on this host ClickHouse and the consumer are short of CPU, not of threads or buffer. On hardware
where ClickHouse does not compete with the clients, `max_insert_threads` may earn its keep; this
machine cannot test that.

## Where the limits are

### The API request path

This is what bites first under a burst.

**Per-collection round trips inside a transaction.** `TimeseriesService.insertDatapoints` is
`@Transactional`, and for each `DatapointsCollection` in a request it does a Postgres lookup
(`findByIdOrExternalId`), an ACL check, a latest-value cache update (a Valkey GET and SET), and
a **synchronous** `allDatapointProducer.send()`. The pooled database connection is held for the
whole loop, including the Pulsar round trip.

That is roughly 1 to 2 ms per collection regardless of how many points the collection carries,
which is why request shape decides everything:

- **1 to 10 points per collection** (one reading per sensor): one instance caps out in the tens
  of thousands of points per second.
- **Thousands of points per collection**: the same overhead amortises to roughly 1M points per
  second.

The fix is to resolve every timeseries in a request with one query, or from a per-instance
cache of externalId to id, value type and dataset id, since that metadata changes rarely and
can be invalidated on timeseries CUD; to check ACLs per dataset rather than per collection; and
to move the publish off the request transaction.

Moving the publish off the transaction is **not** a matter of routing it through
`AfterCommitMessagePublisher`, even though that class exists and every other producer uses it.
That publisher solves a dual-write problem: do not tell Pulsar about something Postgres rolled
back. The datapoint insert path has no dual-write problem to solve, because **it makes no
Postgres writes at all**. It does a read (`findByIdOrExternalId`), an ACL check, a Valkey
write, and the send. The `@Transactional` annotation is wrapping reads only, and there is
nothing to commit.

Publishing after commit here would also be a durability regression. For event CUD, Postgres is
the source of truth and a lost message can be reconciled from it. (Resource CUD no longer
publishes at all: it queues a row in `resource_outbox` inside the writing transaction, which is
what closed the crash-between-commit-and-send gap the publisher's javadoc used to apologise
for.) For datapoint ingest **Pulsar is the source of truth**. There is no Postgres copy to
reconcile from, so a lost message is lost data, and the caller has already been told the write
succeeded.

The fix is instead to drop the transaction, keep the send synchronous so that a 2xx response
still means Pulsar has the data, and let the connection go back to the pool before the network
round trip rather than being held across it. That is also what makes `blockIfQueueFull=true`
safe: blocking on a full queue stops being dangerous once it is not holding a database
connection.

**Per-point work happens about three times.** Each point is parsed once for validation, its
timestamp parsed to `ZonedDateTime` up to twice for latest-value tracking, added to a
collection with a linear scan and a hash insert, and then parsed again by
`DatapointBinaryConverter.toBinary`. Roughly 1 to 2 µs per point. Parsing once into the binary
form and validating on that, comparing epoch milliseconds instead of `ZonedDateTime`, and
appending to a map keyed by timeseries id removes most of it.

**The wire format is JSON strings.** Roughly 60 to 80 bytes per point, and Jackson parsing
dominates once the above is fixed. This only matters for the very high rate class. The
direction that makes it worth doing is broader than performance: the API is increasingly
consumed by SDKs and agents rather than hand-written clients, so most contracts can be
binary-first, with JSON kept as a human-facing rendering rather than the hot path.

### Pulsar

**Topic partitioning.** `all-datapoints` is a 16-partition topic, provisioned before the eager
producer connects. Previously it was a single non-partitioned topic owned by exactly one
broker, which capped the whole path at roughly one broker's throughput regardless of cluster
size. No message key is set, so the producer spreads round-robin across partitions. That is
correct here because the consumer uses a Shared subscription, which gives no ordering guarantee
anyway, and ClickHouse orders by timestamp on read.

Partitions can be raised later but never lowered, and raising them rehashes keys. 16 is a
starting point and should be at least the broker count.

There is a residual race on a brand-new environment: if the consumer subscribes before the API
provisions the topic, Pulsar may auto-create it non-partitioned. The next API startup detects
that and recreates it partitioned. Setting the namespace `autoTopicCreation` policy to
partitioned closes the window completely.

**Producer backpressure.** Explicit batching and generous in-flight headroom, backed by the
client-wide memory limit, let the producer absorb bursts instead of shedding them.
`blockIfQueueFull` defaults to false because the send happens inside a transaction, and
blocking there would hold a pooled database connection and can exhaust the pool, which is a
worse failure than fast-fail. It is a configuration property, so it can be turned on once the
publish moves out of the transaction.

Separately, the namespace `producer_exception` backlog policy is a broker-side limit. No client
tuning prevents send failures once the backlog quota is hit; that is governed by how fast
ClickHouse drains.

**Message shape.** `DataWrapperBin` carries one Avro record plus one `byte[]` per point,
roughly 15 to 20 bytes per point before ZSTD, and one object per point on both encode and
decode. A columnar shape per collection, delta-encoded timestamps as a `long[]` plus one value
`byte[]`, or a ready-made RowBinary or Native block, would reduce the consumer's per-point work
to a copy. Together with raising the partition count this is the Pulsar-side change for the
very high rate class. It is not needed for anything less.

### ClickHouse

This is the real sustained-write ceiling, downstream of Pulsar.

`async_insert=1` with a ZSTD RowBinary stream is a good baseline, and client churn is solved:
`ClickHouseClientPool` holds one long-lived thread-safe `Client` per tenant, process-wide. It
is lazy, so tenants added at runtime get a client on first use; each entry remembers the
connection identity it was built for, so a tenant whose configuration changes transparently
gets a rebuilt client; `invalidate(tenantId)` force-evicts on tenant removal; and everything is
closed on shutdown.

Beyond that, absorbing very high datapoint rates into durable replicated columnar storage is a
sharded ClickHouse problem, and likely a tighter constraint than Pulsar itself.

**Client-side pre-sorting of inserts is not a lever.** MergeTree sorts each insert block by
`ORDER BY (timeseries_id, timestamp)` and skips the permutation when the block already arrives
sorted. For a three-column fixed-width row that sort is tens of milliseconds per million rows,
small next to per-part overhead, ZSTD(9) column compression and the ReplacingMergeTree merge
pass, which runs either way. And with `async_insert=1` the server squashes the listener threads'
inserts into one block before sorting, so per-insert sorting is a no-op. Measure before
touching anything here:

```sql
SELECT event, value FROM system.events
WHERE event IN ('InsertQueryTimeMicroseconds',
                'MergeTreeDataWriterSortingBlocksMicroseconds',
                'MergeTreeDataWriterMergingBlocksMicroseconds',
                'MergeTreeDataWriterBlocksAlreadySorted')
```

The ratio of sorting time to insert time is the ceiling on what pre-sorting could ever save.

**Insert-side levers that do matter**, in order: fewer and larger parts per second, which is
what `async_insert` coalescing already aims at; compression codec choice, since ZSTD(9) mainly
buys insert and merge CPU while DoubleDelta and Gorilla do most of the ratio, so ZSTD(1) or LZ4
is worth measuring on a real partition; and the Native format instead of RowBinary once the
wire is columnar. For the very high rate class the ceiling is a sharded cluster with a
Distributed table or consumer-side routing by tenant or timeseries hash, plus tiered storage.

### Fan-out and subscriptions

**Decode currently runs on every point.** `BatchedDatapointsListener` decodes the whole tenant
batch back to string form before checking whether anything is subscribed, so every point is
decoded even with zero subscribers. Checking the subscription cache first and decoding only the
subscribed collections removes a full pass per point. Small change, clear win.

**The fanout topic has 8 partitions** and each subscriber is a cursor on it, with the
broker-side `SubscriptionKeyEntryFilter` running per message per dispatch. At high datapoint
and subscriber counts that is real broker CPU, and 8 partitions is likely too few. This scales
independently of the ingest path.

### More than one cluster

Everything today is a single `serviceUrl` with singleton `PulsarClient` and `PulsarAdmin`
beans. Two distinct moves live here and should not be conflated:

- **Geo-replication**, for disaster recovery and locality. Pulsar-native and configuration
  level. Each cluster still ingests the full stream, so it does **not** raise the aggregate
  write ceiling.
- **Sharding across clusters**, for throughput. Partition tenants, or timeseries id ranges,
  across N independent clusters each owning a disjoint slice. This needs a client registry
  keyed by tenant or shard plus a routing function, replacing the singleton beans. It is the
  only thing that gets past one cluster's ceiling, and it is only worth doing once the stages
  above have been dealt with.

## Sizing

**Rule of thumb.** At R points per second, c seconds of per-point CPU at a stage needs R times
c cores. At 10⁸ points/s, 10 ns/point is one core and 1 µs/point is 100 cores per stage.

**Sustained 10⁸ points/s.** The API today spends several µs per point plus per-collection round
trips, which would mean hundreds of instances; the request-path work has to improve by roughly
100x. Pulsar would carry about 1 GB/s post-compression before replication, feasible with 64 to
128 partitions and the columnar message shape. The consumer would need roughly 8 to 16
instances once fan-out decode is fixed. ClickHouse would see about 2.4 GB/s raw, roughly 25
TB/day at about 3 bytes per point compressed, needing a sharded cluster. This is an
architecture change, worth pursuing only against a real requirement.

**1B points arriving as an hourly burst, one instance of each service.** That averages about
280k points/s. ClickHouse on one node and a single consumer instance are both fine; the
consumer does over 1M points/s as built, and one node drains 1B in 10 to 20 minutes. Three
things decide the outcome:

1. **API accept rate, which is request shape.** Thousands of points per collection means 1B
   accepted in 15 to 20 minutes, which is fine. One to ten points per collection means hours,
   which is not.
2. **Pulsar backlog quota.** 1B points is roughly 6 to 10 GB stored. Peak backlog is the
   accept rate minus the drain rate, times burst duration. The dev quota of 2 GB is well under
   that, and production should be raised well above it.
3. **Client acknowledgement expectations.** SDK clients with the disk spool tolerate a slow
   API. Plain HTTP clients make point 1 a hard requirement.

The verdict is that this works **if** a load test at the real request shape shows roughly 300k
points/s accepted on one API instance and the backlog stays under quota. The request-path fixes
and the quota increase are worth doing regardless, because they are cheap and they are exactly
what this scenario depends on.

## Open questions

These need production data or a load test, not more reasoning.

- **Burst shape in production.** Points per collection, collections per request, burst
  duration, and whether clients use the SDK with its spool or plain HTTP. This decides the
  hourly-burst scenario outright.
- **What the real target is.** Datapoints or messages, sustained or peak. This decides cluster
  sizing and whether multi-cluster is ever reached.
- **Batch factor distribution** in production. Larger batches mean fewer messages and less
  per-message overhead, and this may be the cheapest lever available.
- **ClickHouse sustained write throughput** per shard, and the shard count the target implies.
- **Broker entry-filter cost** as subscriber count grows.
- **Dev and production namespace policy drift.** `InitNamespaces` applies 2 GB with
  `producer_exception`; `PULSAR_SETUP.md` documents different production values. Worth
  reconciling so that backpressure behaves the same in both.

## Tasks to review

Not scheduled. Revisit this list when there is time, and check the state of each against the
code before acting: several of the earlier items on this list turned out to be partly done
already.

| # | Task | Impact | Effort | Stage |
|---|---|---|---|---|
| 1 | Drop `@Transactional` from `insertDatapoints`, which wraps reads only, so the pooled connection is not held across the Pulsar send; keep the send synchronous; then enable `blockIfQueueFull=true`. Do **not** route this path through `AfterCommitMessagePublisher`, see above | Real blocking backpressure without risking the DB pool, and without a data-loss window | Low to medium | API |
| 2 | Resolve timeseries ids once per request, with a bulk query or a per-instance cache; check ACLs per dataset | Roughly 10x on the small-collection shape | Low to medium | API |
| 3 | Take the latest-value cache off the request path, as one pipelined write per request or by moving it to the consumer | Removes two Valkey round trips per collection | Low | API |
| 4 | Parse each point once: drop the validation re-parse, the `ZonedDateTime` conversions, the hash insert and the linear scan | Roughly 2x per-point CPU on the API | Low | API |
| 5 | Raise the datapoints backlog quota, and reconcile the dev and production policies | Burst absorption | Low | Pulsar |
| 6 | Check the subscription cache before decoding the fan-out batch | Removes a full decode pass per point | Low | Fan-out |
| 7 | Load-test at the real burst shape: accepted points/s on one API instance, Pulsar backlog, consumer drain rate, ClickHouse inserted rows and part counts | Replaces the reasoning above with numbers | Medium | All |
| 8 | Measure the ClickHouse insert-time breakdown through `system.events` before any insert tuning, then move the datapoint columns to ZSTD(3): measured at the insert CPU of LZ4 for 17 percent less disk, and note that the tenant manager's schema copy provisions production on LZ4 while this repository's says ZSTD(9) | Avoids tuning folklore; an insert and merge CPU win | Low | ClickHouse |
| 9 | Set the namespace `autoTopicCreation` policy to partitioned | Closes the non-partitioned auto-create race | Low | Pulsar |
| 10 | Raise fanout partitions and measure entry-filter CPU on the brokers | Subscription-path headroom | Low to medium | Fan-out |
| 11 | Cluster-routing abstraction: a client registry plus a tenant-to-cluster map | Makes sharding possible later without a rewrite | Medium | Pulsar |
| 12 | ~~Binary-first bulk ingest contract: Arrow IPC frames, compressed per frame by the client, carried untouched through a topic of their own and merged by the consumer~~ **Built.** See [the binary ingest path](#the-binary-ingest-path-measured) above for the decision and the measurement | Very high rate class only | High | API, Pulsar, ClickHouse |
| 13 | Shard ClickHouse, with a Distributed table or consumer-side routing, plus tiered storage | Very high rate class only | High | ClickHouse |
| 14 | Shard across Pulsar clusters | True horizontal scale, only if a load test proves it necessary | High | Pulsar |
