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
| Flows, versions, deployments, identities, and the schedule each was deployed with | Postgres | Authoritative, as before. A deployment, a switch, a refused identity or a deleted dataset copies what dispatch and the ticker need into KVRocks at once; the sweep does it for every flow every `resync-interval` (5 min). |
| How far a schedule has got (its next tick) | KVRocks | Moved on by the ticker with every run it makes. Postgres keeps a checkpoint of it (`next_tick`, `next_due_at`, `last_tick`), saved every `checkpoint-interval` (1 min), and the Runs overview reads that. A KVRocks that is lost starts the schedules again from it. |
| A run while pending, running or recently finished | KVRocks | The only copy. |
| A finished run, for good | ClickHouse `flow_run` | One row per finished run. Written from KVRocks by the archiver. |
| A run's records | ClickHouse `flow_run_event` | Unchanged, through Pulsar. |

This is an exception to CONSTRAINTS.md invariant 2 and is written there narrowly: Postgres stays
authoritative for everything except run state.

## Keys

One KVRocks per tenant, so keys carry no tenant. Times are epoch milliseconds taken from the KVRocks
server clock (`TIME`), so no instance's clock decides a lease or a back-off.

| Key | Type | Holds |
|---|---|---|
| `flow:run:{id}` | hash | every field of the run: flow, version, status, attempt, max attempts, window, parameters, trigger, who, `run_as`, owner, `cancel_requested`, `preview`, `definition` and `data_set_id` (previews), timestamps, error, stats, `failure_event_owed` |
| `flow:q:{flow}` | sorted set | the flow's pending runs ready to start. Score is the window start times 4096 plus a sequence number, so earliest window first and creation order within a window |
| `flow:ready` | sorted set | a **hint**: every flow that has a pending run and room under its limit, and possibly some that no longer do. Score is the score of the flow's first run when it was last put there. Dispatch reads only this, and whoever finds a flow in it with nothing to start takes it out |
| `flow:d:{flow}` | sorted set | the flow's runs waiting out a retry back-off, scored by when they may start |
| `flow:delayed` | sorted set | the same runs of all flows, which is where dispatch finds the ones whose time has come |
| `flow:running:{flow}` | set | the flow's running runs. Its size is how many are running |
| `flow:retrying:{flow}` | set | the flow's pending runs on a second or later attempt |
| `flow:meta:{flow}` | hash | `enabled`, `deployed`, `max_concurrent`, `external_id`, `data_set_id`. Copied from Postgres each sweep |
| `flow:sched` | sorted set | flows with a schedule, by when their next tick is due (tick plus delay); a schedule with no tick left is scored far in the future. The ticker reads only this |
| `flow:sched:{flow}` | hash | the schedule and what a run of it needs: `trigger`, `next_tick_ms`, `last_tick_ms`, `deployed_us` (the deployment it belongs to), `version`, `max_attempts`, `timeout_ms`, `run_as`, `external_id`, `data_set_id`, `delay_ms` |
| `flow:sched-lock:{flow}` | string | the instance making a flow's ticks right now; expires, so a dead one holds nothing for long |
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

Every change of a run's state is an **optimistic transaction** in `RunStore`, in Java: `WATCH` the
run's hash, read it, decide, and write the run and the indexes around it in one `MULTI`/`EXEC`. If
anything else wrote the run in between, KVRocks discards the transaction and the store reads again and
decides again, after a short random wait, up to 30 times (then `RunStore.Contended`). So a transition
and its indexes move together or not at all, and a write by the owner of a run is compare-and-set on
`owner` and `attempt`, as it was in SQL. The operations are **enqueue**, **claim**, **renew**,
**finish** (complete, retry, release), **reap** (requeue or fail an expired lease), **cancel**,
**sync a flow's limits**, and the archiver's **take** and **archived**.

A transaction cannot share a connection, so each borrows one of `TenantKvrocks`'s dedicated
connections (16 per tenant per instance) for its length; plain reads use the shared one.

- **Claim** takes up to *n* runs: it moves retries whose time has come into their queues (`flow:delayed`),
  then walks `flow:ready`, and for each flow takes its first run if its limit leaves room. It watches
  the run and the flow's running set, never the queue, because every enqueue would then abort it. Two
  instances after the same flow both read its first run and conflict on it; the loser looks again and
  takes the next. A flow with nothing to start is taken out of `ready` and looked at again
  (`settleReady`), so a run enqueued or a slot freed in between puts it back. That is what keeps a
  backlog of one flow from hiding another's runs, and why dispatch costs the number of flows with work,
  not the size of a queue.
- **Enqueue is idempotent.** A run made for a schedule tick, or as a re-run of a failed run, gets an id
  derived from what it is for, and enqueueing an id that exists does nothing. So the sweep enqueues
  first and advances the schedule in Postgres second, and a crash between them repeats ticks without
  duplicating runs; and asking twice for the same re-run queues one run.
- **Retry waits are jittered.** A failed attempt waits 30 s doubling per attempt up to 15 min, and each wait is
  drawn from its upper half (half of it to all of it), so runs that failed together because something they
  share was down do not retry in the same instant. The reaper's requeue after a lost lease does the same.
- **The ticker** (every `tick-interval`, 1 s, on every instance) makes the runs of the schedules that are
  due, and asks Postgres nothing. An instance takes a flow with `flow:sched-lock:{flow}` so that they do
  not all do the same work, makes its due ticks into runs (oldest first, `catch-up` at a time, and on while
  more are due for up to two seconds), and then moves the schedule on from the tick it saw
  (`advanceSchedule`, a transaction on the schedule's hash). The runs come first and the schedule second,
  so a crash between them repeats ticks, which are the same runs, and never skips one; a lock whose
  holder died frees itself by time, and the flow's ticks are made up for in one go. A redeployment starts
  its schedule at its first tick; a resync of the same deployment leaves it where it is.
- **Idle instances look for themselves.** The dispatcher of an instance with a free slot looks for runs
  every `dispatch-poll` (1 s). Without that, runs queued by the instance that happened to tick would
  reach the others only at their next sweep, and they would sit idle while a backlog waits.
- **The archiver's take** is exclusive by a token key per run (`SET NX`), not a transaction: every
  finishing run writes the set it reads, so a transaction on it would starve.
- **No schema constraints any more.** `run_running_has_lease`, the status check and the foreign key to
  the flow's version were Postgres invariants. They are now the store's and are tested as such, after
  random sequences of operations and under concurrent load from several instances (`RunStoreIT`): a
  running run has an owner and a lease; a run is in exactly one of pending, delayed, running or
  finished; a flow's running set holds exactly its running runs, and never more than its limit while
  runs are being started; a flow with a pending run and room is in `ready`.

## Decisions taken, and the price

1. **One KVRocks per tenant, no replica.** It writes to disk, so a restart loses nothing, and there is
   no replication to lose a lease. If it is down, that tenant's runs do not start, queue or show in the
   console; other tenants are unaffected. A run in progress finishes its work and then cannot record it,
   so it fails to finish and its lease expires. Losing the disk loses the tenant's queued and running
   runs: schedules start again from their last checkpoint (at most `checkpoint-interval` of ticks are
   made again), and the failed windows are in ClickHouse
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

The sweep stays at ten seconds and no longer makes runs: it tells the run store the flows again at a tenant's
first sweep and then every `resync-interval`, saves the schedules' positions to Postgres, and writes the
lifecycle events runs could not write. A quiet tenant costs Postgres nothing between those. Every tick of a
schedule is made into a run, however fine the schedule: a flow that ticks every second gets each run within
about a `tick-interval` of its tick, and the service refuses to start with a `catch-up` below twice the seconds in
`tick-interval`, which would let such a flow run only some of its ticks. A pass of the ticker makes up to 2 s
of enqueues per flow, so a backlog of an hour of per-second ticks is worked off in a few passes.

## Build order

1. KVRocks client and the store with its transactions, tested against a real KVRocks, not yet used.
2. The ClickHouse `flow_run` table, the archiver and the history reads, tested against a real
   ClickHouse, not yet used.
3. The coordinator, service, records, events, overview and cleanup switched over, the tests that were
   written against Postgres ported to the store, the `flow.run` table dropped.
