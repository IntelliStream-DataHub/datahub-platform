# Where flow runs live

Flow runs are tracked in each tenant's **KVRocks** while they are queued, running or just finished,
and kept as history in the tenant's **ClickHouse**. Postgres keeps flows, their versions, their
deployments and their schedules, and no longer has a table of runs. This replaces the plan's
2026-10-12 decision to coordinate runs in Postgres "for now" (INTELLISTREAM_FLOW_PLAN.md, *Where runs
are coordinated*), which named this move and its price.

## Why

A run costs Postgres about three transactions and two row updates. That is fine at hundreds of runs a
second and not at ten thousand: the hot path (claim, lease, finish, per-flow counts) is bookkeeping
that does not need a relational store, and a row per run for ninety days is not something Postgres
should hold at that rate. KVRocks takes the bookkeeping; ClickHouse takes the history, as it already
does for datapoints and for a run's records.

## What stays where

| Data | Store | Notes |
|---|---|---|
| Flows, versions, deployments, identities, schedule (`next_tick`, `next_due_at`, `last_tick`) | Postgres | Authoritative, as before. The sweep copies what dispatch needs into KVRocks every sweep interval. |
| A run while pending, running or recently finished | KVRocks | The only copy. |
| A finished run, for good | ClickHouse `flow_run` | One row per finished run. Written from KVRocks by the archiver. |
| A run's records | ClickHouse `flow_run_event` | Unchanged, through Pulsar. |

This is an exception to CONSTRAINTS.md invariant 2 and is written there narrowly: Postgres stays
authoritative for everything except run state.

## Keys

One KVRocks per tenant, so keys carry no tenant. Times are epoch milliseconds taken from the KVRocks
server clock inside scripts (`TIME`), so no instance's clock decides a lease or a back-off.

| Key | Type | Holds |
|---|---|---|
| `flow:run:{id}` | hash | every field of the run: flow, version, status, attempt, max attempts, window, parameters, trigger, who, `run_as`, owner, `cancel_requested`, `preview`, `definition` and `data_set_id` (previews), timestamps, error, stats, `failure_event_owed` |
| `flow:q:{flow}` | sorted set | the flow's pending runs ready to start. Score is the window start times 4096 plus a sequence number, so earliest window first and creation order within a window |
| `flow:ready` | sorted set | flows that have a pending run and room under their limit. Score is the score of the flow's first run. Dispatch reads only this |
| `flow:delayed` | sorted set | runs waiting out a retry back-off, scored by when they may start |
| `flow:meta:{flow}` | hash | `enabled`, `deployed`, `max_concurrent`, `running`, `external_id`, `data_set_id`. Copied from Postgres each sweep; `running` is kept by the scripts |
| `flow:leases` | sorted set | running runs by lease expiry |
| `flow:deadlines` | sorted set | running runs by start plus timeout plus grace, for the reaper's cancel |
| `flow:owed` | set | runs that owe a lifecycle event |
| `flow:failed-windows:{flow}` | set | window starts that ended in a failure with no success since |
| `flow:last:{flow}` | hash | the flow's latest finished run: id, status, error code, finished at |
| `flow:unarchived` | sorted set | finished runs not yet in ClickHouse, by finish time |

A finished run's hash stays for `datahub.flow.runs.finished-ttl` (default 15 minutes) after
ClickHouse has it, so a run that just ended is readable at once and the console's run page never
sees a gap.

## Operations

Every change of a run's state is one Lua script, so a transition and the indexes around it move
together or not at all: **enqueue**, **claim**, **renew**, **finish** (complete, retry, release),
**reap** (requeue or fail an expired lease), **cancel**, **sync a flow's limits**. Writes by the
owner of a run are compare-and-set on `owner` and `attempt`, as they were in SQL.

- **Claim** takes up to *n* runs: it moves retries whose time has come into their queues, then walks
  `flow:ready`, and for each flow takes the first runs that its limit leaves room for. A flow that
  reaches its limit leaves `ready`; the finish of one of its runs puts it back. That is what keeps a
  backlog of one flow from hiding another's runs, and why dispatch costs the number of flows with
  work, not the size of a queue.
- **Enqueue is idempotent.** A run made for a schedule tick, or as a re-run of a failed run, gets an id
  derived from what it is for, and enqueueing an id that exists does nothing. So the sweep enqueues
  first and advances the schedule in Postgres second, and a crash between them repeats ticks without
  duplicating runs; and asking twice for the same re-run queues one run.
- **No schema constraints any more.** `run_running_has_lease`, the status check and the foreign key to
  the flow's version were Postgres invariants. They are now the scripts' and are tested as such: a
  running run has an owner and a lease; a run is in exactly one of pending, delayed, running or
  finished; a flow's `running` equals the number of its running runs; a flow is in `ready` exactly
  when it has a pending run and room.

## Decisions taken, and the price

1. **One KVRocks per tenant, no replica.** It writes to disk, so a restart loses nothing, and there is
   no replication to lose a lease. If it is down, that tenant's runs do not start, queue or show in the
   console; other tenants are unaffected. A run in progress finishes its work and then cannot record it,
   so it fails to finish and its lease expires. Losing the disk loses the tenant's queued and running
   runs: schedules re-create their ticks from `last_tick`, and the failed windows are in ClickHouse
   and the lifecycle events, but queued manual runs are gone.
2. **A run that just finished** stays readable from KVRocks (see above), then from ClickHouse.
3. **Recent history** is not kept in KVRocks beyond the finished TTL and each flow's latest run. The
   flow page's list is the live runs from KVRocks followed by finished ones from ClickHouse.
4. **Previews** are runs like the rest, never queued, claimed by id on the instance that made them, and
   expire after the finished TTL.
5. **Existing runs in the dev tenants' Postgres** are dropped with the table. The feature is not
   released.
6. **Commits** are new ones, not fixups: this rewrites the coordination commit's content.

## What is not changed

The sweep stays at ten seconds and does what it did: ticks into runs, and now flows' limits into
KVRocks. Dispatch is event-driven (RunCoordinator) and unchanged in how it is woken. Memory per run,
slots, the lease fence and the records pipeline are unchanged. Run history at ten thousand runs a
second is ClickHouse's to hold; one row per finished run is inserted in batches.

## Build order

1. KVRocks client and the store with its scripts, tested against a real KVRocks, not yet used.
2. The ClickHouse `flow_run` table, the archiver and the history reads, tested against a real
   ClickHouse, not yet used.
3. The coordinator, service, records, events, overview and cleanup switched over, the tests that were
   written against Postgres ported to the store, the `flow.run` table dropped.
