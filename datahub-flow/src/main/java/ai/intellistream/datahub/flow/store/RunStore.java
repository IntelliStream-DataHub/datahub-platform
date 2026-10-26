// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.tenant.TenantContext;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Where flow runs are tracked while they are queued, running or just finished: the current tenant's
 * KVRocks (RUN_STORE.md has the keys).
 *
 * <p>Every change of a run's state is an optimistic transaction: {@code WATCH} the run's hash, read
 * it, decide, and write the run and the indexes around it in one {@code MULTI}/{@code EXEC}. If
 * anything else wrote the run in between, the transaction is discarded and decided again from what
 * is there now, so a run and its indexes move together or not at all, and a write that depends on
 * who owns the run is compare-and-set. The clock is the server's ({@code TIME}). A transaction
 * that cannot get through in {@link #ATTEMPTS} tries throws {@link Contended}.
 *
 * <p>Only the run's hash is the truth. {@code flow:ready} is a hint, a superset of the flows that
 * have a pending run and room: a transaction that takes a run never needs to watch the queue (every
 * enqueue would abort it), and whoever finds a flow in it with nothing to start takes it out and
 * looks again ({@link #settleReady}), so a run enqueued meanwhile is not lost.
 *
 * <p>This is the only copy of a run that has not ended. A run that has is archived to ClickHouse
 * ({@code unarchived}, {@code archived}) and its hash expires a while after.
 *
 * <p>Calls go to the KVRocks of the tenant on {@link TenantContext}, as the Postgres side routes by
 * the same context.
 */
@Component
public class RunStore {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /** Tries of one transaction before it gives up on a key that others keep changing. */
    private static final int ATTEMPTS = 30;
    private static final String LIVE = "flow:live";
    private static final String READY = "flow:ready";
    private static final String DELAYED = "flow:delayed";
    private static final String LEASES = "flow:leases";
    private static final String DEADLINES = "flow:deadlines";
    private static final String UNARCHIVED = "flow:unarchived";
    private static final String OWED = "flow:owed";
    private static final String SCHEDULES = "flow:sched";
    /** The score of a schedule that has no tick left: after every clock there will be. */
    private static final double NEVER = 9.0e15;

    /** What a new run is made with. {@code id} is derived from what the run is for, so a repeat is a no-op. */
    public record NewRun(UUID id, long flowId, String flowExternalId, long dataSetId, int version,
                         int maxAttempts, Duration timeout, TimeWindow window, String parameters,
                         String trigger, String triggeredBy, String runAs, Instant notBefore) {
    }

    /** A preview: of a saved version ({@code flowId}, {@code version}) or of an unsaved {@code definition}. */
    public record NewPreview(UUID id, Long flowId, Integer version, String flowExternalId, String definition,
                             Long dataSetId, Duration timeout, TimeWindow window, String parameters,
                             String triggeredBy) {
    }

    /** A run as stored. */
    public record StoredRun(UUID id, Long flowId, String flowExternalId, Long dataSetId, Integer version,
                            String status, int attempt, int maxAttempts, Instant notBefore,
                            boolean cancelRequested, boolean preview, boolean failureEventOwed,
                            Duration timeout, String owner, TimeWindow window, String parameters,
                            String processorVersions, String definition, String trigger, String triggeredBy,
                            String runAs, Instant createdAt, Instant startedAt, Instant finishedAt,
                            String errorCode, String error, String stats) {
    }

    /** A run this instance now owns. */
    public record Claimed(UUID id, Long flowId, String flowExternalId, Long dataSetId, Integer version,
                          String definition, int attempt, int maxAttempts, Duration timeout, TimeWindow window,
                          String parameters, boolean preview, String runAs, Duration leaseLeft) {
    }

    /** A renewed lease. */
    public record Renewed(UUID id, boolean cancelRequested, Duration leaseLeft) {
    }

    /** What the reaper did with a run whose lease ran out. */
    public record Reaped(UUID id, boolean requeued, int attempt) {
    }

    /** What an attempt leaves on the run however it ended. */
    public record Outcome(String errorCode, String error, String parameters, String processorVersions,
                          String stats) {
    }

    /** How a one-run claim ended. */
    public enum Claim { CLAIMED, NOT_CLAIMED, CANCELLED }

    /** What dispatch needs to know of a flow. */
    public record FlowLimits(long flowId, String externalId, long dataSetId, boolean enabled, boolean deployed,
                             int maxConcurrentRuns) {
    }

    /**
     * A flow's schedule as it is handed to the run store when the flow is deployed, switched or told
     * again: what the ticker needs to make its runs. {@code firstTick} is where the schedule starts if
     * the run store knows nothing of this deployment yet; if it does, it is further on, and stays.
     */
    public record ScheduleSpec(long flowId, String externalId, long dataSetId, int version, int maxAttempts,
                               Duration timeout, String runAs, String trigger, Duration delay, Instant deployedAt,
                               Instant firstTick) {
    }

    /**
     * A schedule as the ticker works from it.
     *
     * @param nextTick the tick it waits for; null when the cron expression has no more
     * @param lastTick the latest tick made into a run, null if none yet
     */
    public record ScheduleRecord(long flowId, String externalId, long dataSetId, int version, int maxAttempts,
                                 Duration timeout, String runAs, String trigger, Duration delay, Instant deployedAt,
                                 Instant nextTick, Instant lastTick) {
    }

    /** A flow's latest finished run. */
    public record LastRun(UUID id, String status, String errorCode, Instant finishedAt) {
    }

    /** What a flow has in hand now. */
    public record FlowState(long flowId, int running, int queued, int retrying, Instant oldestWaiting,
                            String runningOn, Instant runningSince, LastRun last) {
    }

    /**
     * A wait spread over its upper half: between half of it and all of it. Runs that fail together,
     * as they do when something they all depend on is down, then do not all come back together, and
     * the configured wait stays the longest.
     */
    public static Duration jittered(Duration wait) {
        long ms = wait.toMillis();
        return Duration.ofMillis(ms / 2 + ThreadLocalRandom.current().nextLong(ms - ms / 2 + 1));
    }

    /** A transaction gave up after {@link #ATTEMPTS} tries: the keys it needs are being changed too often. */
    public static class Contended extends IllegalStateException {
        Contended(String message) {
            super(message);
        }
    }

    /** What one try of a transaction decided: the answer, and the writes it stands on (none: it stands as read). */
    private record Verdict<T>(T answer, Consumer<RedisAsyncCommands<String, String>> writes) {
        static <T> Verdict<T> of(T answer) {
            return new Verdict<>(answer, null);
        }
    }

    /** Reads what it needs after watching it, and decides. */
    @FunctionalInterface
    private interface Attempt<T> {
        Verdict<T> read(RedisCommands<String, String> c);
    }

    private final TenantKvrocks kvrocks;
    private final Duration finishedTtl;

    public RunStore(TenantKvrocks kvrocks,
                    @Value("${datahub.flow.runs.finished-ttl:PT15M}") Duration finishedTtl) {
        this.kvrocks = kvrocks;
        this.finishedTtl = finishedTtl;
    }

    // ---------------------------------------------------------------- the clock

    /** The server's clock, which decides every lease and back-off. */
    public Instant now() {
        return Instant.ofEpochMilli(now(connection().sync()));
    }

    private static long now(RedisCommands<String, String> c) {
        List<String> time = c.time();
        return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
    }

    // ---------------------------------------------------------------- making runs

    /** Makes the run pending; false when a run with this id exists already. */
    public boolean enqueue(NewRun r) {
        Map<String, String> f = new LinkedHashMap<>();
        pair(f, "flow_external_id", r.flowExternalId());
        pair(f, "data_set_id", r.dataSetId());
        pair(f, "version", r.version());
        pair(f, "max_attempts", r.maxAttempts());
        pair(f, "timeout_ms", r.timeout().toMillis());
        window(f, r.window());
        pair(f, "parameters", r.parameters() == null ? "{}" : r.parameters());
        pair(f, "trigger", r.trigger());
        pair(f, "triggered_by", r.triggeredBy());
        pair(f, "run_as", r.runAs());
        return make(r.id(), String.valueOf(r.flowId()), false, r.window(),
                r.notBefore() == null ? 0 : r.notBefore().toEpochMilli(), f);
    }

    /** Makes each run pending; how many were new. */
    public int enqueueAll(List<NewRun> runs) {
        int made = 0;
        for (NewRun run : runs) {
            if (enqueue(run)) {
                made++;
            }
        }
        return made;
    }

    /** Makes a preview, which is never queued: its instance takes it by id. */
    public void enqueuePreview(NewPreview p) {
        Map<String, String> f = new LinkedHashMap<>();
        pair(f, "flow_external_id", p.flowExternalId());
        pair(f, "data_set_id", p.dataSetId());
        pair(f, "version", p.version());
        pair(f, "max_attempts", 1);
        pair(f, "timeout_ms", p.timeout().toMillis());
        window(f, p.window());
        pair(f, "parameters", p.parameters() == null ? "{}" : p.parameters());
        pair(f, "definition", p.definition());
        pair(f, "trigger", "preview");
        pair(f, "triggered_by", p.triggeredBy());
        pair(f, "run_as", p.triggeredBy());
        make(p.id(), p.flowId() == null ? "" : String.valueOf(p.flowId()), true, p.window(), 0, f);
    }

    /**
     * Makes the run, unless one with this id exists. Its place in the queue is its window's start times
     * 4096 plus a sequence number: the earliest window first, and creation order within a window.
     */
    private boolean make(UUID uuid, String flow, boolean preview, TimeWindow window, long notBefore,
                         Map<String, String> fields) {
        String id = uuid.toString();
        String rk = runKey(id);
        return optimistic(c -> {
            c.watch(rk);
            if (c.exists(rk) == 1) {
                return Verdict.of(false);
            }
            long now = now(c);
            long seq = c.incr("flow:seq");
            long base = window == null ? now : window.start().toEpochMilli();
            String score = Long.toString(base * 4096 + seq % 4096);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("id", id);
            h.put("flow_id", flow);
            h.put("status", "PENDING");
            h.put("attempt", "1");
            h.put("created_ms", String.valueOf(now));
            h.put("score", score);
            h.put("not_before_ms", String.valueOf(notBefore));
            h.put("cancel_requested", "0");
            h.put("failure_event_owed", "0");
            h.put("preview", preview ? "1" : "0");
            h.putAll(fields);
            Double hint = preview ? null : readyScore(c, flow, notBefore > now ? null : Double.parseDouble(score));
            return new Verdict<>(true, w -> {
                w.hset(rk, h);
                w.zadd(LIVE, now, id);
                if (!preview) {
                    makePending(w, flow, id, score, notBefore, now);
                    if (hint != null) {
                        w.zadd(READY, hint, flow);
                    }
                }
            });
        });
    }

    // ---------------------------------------------------------------- starting and keeping runs

    /** What one look at a flow's queue came to. */
    private enum Tried { CLAIMED, CANCELLED, STALE, BLOCKED, DRAINED, CONTENDED }

    private record Taken(Tried tried, String id) {
    }

    /**
     * Takes up to {@code most} pending runs for {@code owner}: earliest windows first, each flow only
     * as many as its limit leaves room for, the runs of a flow switched off cancelled instead.
     */
    public List<Claimed> claim(String owner, Duration lease, int most, Duration grace) {
        promote();
        var sync = connection().sync();
        List<String> ids = new ArrayList<>();
        Set<String> skip = new HashSet<>();
        for (int guard = 0; ids.size() < most && guard < most * 10 + 100; guard++) {
            String flow = sync.zrange(READY, 0, skip.size()).stream().filter(f -> !skip.contains(f))
                    .findFirst().orElse(null);
            if (flow == null) {
                break;
            }
            Taken taken = claimFrom(flow, owner, lease, grace);
            switch (taken.tried()) {
                case CLAIMED -> ids.add(taken.id());
                case BLOCKED, DRAINED -> {
                    settleReady(flow);
                    skip.add(flow);
                }
                case CONTENDED -> skip.add(flow);
                case CANCELLED, STALE -> {
                }
            }
        }
        return claimed(ids, lease);
    }

    /**
     * Takes the first pending run of the flow. Only the run and the flow's running set are watched: the
     * queue is not, so enqueues do not stand in the way, and two instances after the same flow
     * conflict on the run, the loser looking again at the next one.
     */
    private Taken claimFrom(String flow, String owner, Duration lease, Duration grace) {
        try {
            return optimistic(c -> {
                Map<String, String> meta = c.hgetall(metaKey(flow));
                c.watch(runningKey(flow));
                List<String> head = c.zrange(queueKey(flow), 0, 0);
                if (head.isEmpty()) {
                    return Verdict.of(new Taken(Tried.DRAINED, null));
                }
                String id = head.get(0);
                String rk = runKey(id);
                c.watch(rk);
                Map<String, String> h = c.hgetall(rk);
                long now = now(c);
                if (!"PENDING".equals(h.get("status"))) {
                    // An entry left behind by a run that ended or was purged.
                    return new Verdict<>(new Taken(Tried.STALE, id), w -> w.zrem(queueKey(flow), id));
                }
                if (!usable(meta)) {
                    return new Verdict<>(new Taken(Tried.CANCELLED, id), w -> disabled(w, flow, id, now));
                }
                if (c.scard(runningKey(flow)) >= limit(meta)) {
                    return Verdict.of(new Taken(Tried.BLOCKED, null));
                }
                return new Verdict<>(new Taken(Tried.CLAIMED, id), w -> start(w, flow, id, h, owner, now, lease, grace));
            });
        } catch (Contended e) {
            return new Taken(Tried.CONTENDED, null);
        }
    }

    /** Takes this one run; for a manual start, or a preview on the instance that made it. */
    public Claim claimOne(UUID uuid, String owner, Duration lease, Duration grace) {
        String id = uuid.toString();
        String rk = runKey(id);
        return optimistic(c -> {
            c.watch(rk);
            Map<String, String> h = c.hgetall(rk);
            if (!"PENDING".equals(h.get("status")) || "1".equals(h.get("cancel_requested"))) {
                return Verdict.of(Claim.NOT_CLAIMED);
            }
            long now = now(c);
            String flow = h.get("flow_id");
            if ("1".equals(h.get("preview"))) {
                return new Verdict<>(Claim.CLAIMED, w -> start(w, null, id, h, owner, now, lease, grace));
            }
            // The flow's limit still governs a manual start.
            c.watch(runningKey(flow));
            if (c.zscore(queueKey(flow), id) == null) {
                return Verdict.of(Claim.NOT_CLAIMED);
            }
            Map<String, String> meta = c.hgetall(metaKey(flow));
            if (!usable(meta)) {
                return new Verdict<>(Claim.CANCELLED, w -> disabled(w, flow, id, now));
            }
            if (c.scard(runningKey(flow)) >= limit(meta)) {
                return Verdict.of(Claim.NOT_CLAIMED);
            }
            return new Verdict<>(Claim.CLAIMED, w -> start(w, flow, id, h, owner, now, lease, grace));
        });
    }

    /** The run is running, for {@code owner}, until its lease runs out; {@code flow} null: a preview, which no flow governs. */
    private static void start(RedisAsyncCommands<String, String> w, String flow, String id, Map<String, String> h,
                              String owner, long now, Duration lease, Duration grace) {
        String rk = runKey(id);
        if (flow != null) {
            w.zrem(queueKey(flow), id);
            w.sadd(runningKey(flow), id);
            w.srem(retryingKey(flow), id);
        }
        w.hset(rk, Map.of("status", "RUNNING", "owner", owner, "started_ms", String.valueOf(now),
                "lease_ms", String.valueOf(now + lease.toMillis())));
        w.hdel(rk, "finished_ms");
        w.zadd(LEASES, now + lease.toMillis(), id);
        long timeout = h.get("timeout_ms") == null ? 0 : Long.parseLong(h.get("timeout_ms"));
        w.zadd(DEADLINES, now + timeout + grace.toMillis(), id);
    }

    /** A pending run of a flow that was switched off ends cancelled, not started. */
    private static void disabled(RedisAsyncCommands<String, String> w, String flow, String id, long now) {
        w.zrem(queueKey(flow), id);
        w.srem(retryingKey(flow), id);
        w.hset(runKey(id), Map.of("status", "CANCELLED", "error_code", "FLOW_DISABLED",
                "error", "The flow was switched off before the run could start.", "finished_ms", String.valueOf(now)));
        ended(w, flow, id, "CANCELLED", "FLOW_DISABLED", false, null, now);
    }

    /** The claimed run, read back; empty if it ended in the meantime. */
    public Optional<Claimed> claimedRun(UUID id, Duration lease) {
        return claimed(List.of(id.toString()), lease).stream().findFirst();
    }

    private List<Claimed> claimed(List<String> ids, Duration lease) {
        List<Claimed> out = new ArrayList<>();
        for (Map<String, String> h : hashes(ids)) {
            out.add(new Claimed(uuid(h, "id"), longOrNull(h, "flow_id"), str(h, "flow_external_id"),
                    longOrNull(h, "data_set_id"), intOrNull(h, "version"), str(h, "definition"),
                    Integer.parseInt(h.get("attempt")), Integer.parseInt(h.get("max_attempts")),
                    Duration.ofMillis(Long.parseLong(h.get("timeout_ms"))), window(h), str(h, "parameters"),
                    "1".equals(h.get("preview")), str(h, "run_as"), lease));
        }
        return out;
    }

    /** Renews the leases {@code owner} still holds; a run missing from the answer is no longer its. */
    public List<Renewed> renew(Collection<UUID> ids, String owner, Duration lease) {
        List<Renewed> renewed = new ArrayList<>();
        for (UUID uuid : ids) {
            String id = uuid.toString();
            String rk = runKey(id);
            Renewed r = optimistic(c -> {
                c.watch(rk);
                Map<String, String> h = c.hgetall(rk);
                if (!"RUNNING".equals(h.get("status")) || !owner.equals(h.get("owner"))) {
                    return Verdict.of(null);
                }
                long until = now(c) + lease.toMillis();
                return new Verdict<>(new Renewed(uuid, "1".equals(h.get("cancel_requested")), lease), w -> {
                    w.zadd(LEASES, until, id);
                    w.hset(rk, "lease_ms", String.valueOf(until));
                });
            });
            if (r != null) {
                renewed.add(r);
            }
        }
        return renewed;
    }

    // ---------------------------------------------------------------- ending attempts

    /** The run ended for good. False when this owner no longer holds the attempt. */
    public boolean complete(UUID id, String owner, int attempt, String status, boolean failureEventOwed,
                            Outcome outcome) {
        return finish(id, owner, attempt, "complete", status, Duration.ZERO, failureEventOwed, outcome(outcome, true));
    }

    /** The attempt failed and another is allowed: pending again after {@code backoff}. */
    public boolean retry(UUID id, String owner, int attempt, Duration backoff, Outcome outcome) {
        return finish(id, owner, attempt, "retry", "PENDING", backoff, false, outcome(outcome, false));
    }

    /** Hands the run back unchanged, as this instance shuts down: the attempt does not count. */
    public boolean release(UUID id, String owner, int attempt) {
        return finish(id, owner, attempt, "release", "PENDING", Duration.ZERO, false, Map.of());
    }

    /** Only the owner of the current attempt may finish it. */
    private boolean finish(UUID uuid, String owner, int attempt, String kind, String status, Duration backoff,
                           boolean owed, Map<String, String> outcome) {
        String id = uuid.toString();
        String rk = runKey(id);
        return optimistic(c -> {
            c.watch(rk);
            Map<String, String> h = c.hgetall(rk);
            if (!"RUNNING".equals(h.get("status")) || !owner.equals(h.get("owner"))
                    || Integer.parseInt(h.get("attempt")) != attempt) {
                return Verdict.of(false);
            }
            String flow = h.get("flow_id");
            boolean preview = "1".equals(h.get("preview"));
            String windowStart = h.get("window_start_ms");
            String score = h.get("score");
            long now = now(c);
            boolean queuedNow = kind.equals("release") || (kind.equals("retry") && backoff.isZero());
            Double hint = preview ? null : readyScore(c, flow, queuedNow ? Double.parseDouble(score) : null);
            return new Verdict<>(true, w -> {
                w.zrem(LEASES, id);
                w.zrem(DEADLINES, id);
                if (!preview) {
                    w.srem(runningKey(flow), id);
                }
                if (!outcome.isEmpty()) {
                    w.hset(rk, outcome);
                }
                w.hdel(rk, "owner", "lease_ms");
                switch (kind) {
                    case "complete" -> {
                        w.hset(rk, Map.of("status", status, "finished_ms", String.valueOf(now),
                                "failure_event_owed", owed ? "1" : "0"));
                        if (owed) {
                            w.sadd(OWED, id);
                        }
                        ended(w, flow, id, status, outcome.getOrDefault("error_code", h.get("error_code")),
                                preview, windowStart, now);
                    }
                    case "retry" -> {
                        long notBefore = now + backoff.toMillis();
                        w.hset(rk, Map.of("status", "PENDING", "attempt", String.valueOf(attempt + 1),
                                "not_before_ms", String.valueOf(notBefore)));
                        w.hdel(rk, "started_ms");
                        makePending(w, flow, id, score, notBefore, now);
                        w.sadd(retryingKey(flow), id);
                    }
                    default -> {
                        w.hset(rk, "status", "PENDING");
                        w.hdel(rk, "started_ms");
                        if (!preview) {
                            makePending(w, flow, id, score, 0, now);
                            if (attempt > 1) {
                                w.sadd(retryingKey(flow), id);
                            }
                        }
                    }
                }
                if (hint != null) {
                    w.zadd(READY, hint, flow);
                }
            });
        });
    }

    private static Map<String, String> outcome(Outcome o, boolean withParameters) {
        Map<String, String> a = new LinkedHashMap<>();
        pair(a, "error_code", o.errorCode() == null ? "" : o.errorCode());
        pair(a, "error", o.error() == null ? "" : o.error());
        if (withParameters && o.parameters() != null) {
            pair(a, "parameters", o.parameters());
        }
        if (o.processorVersions() != null) {
            pair(a, "processor_versions", o.processorVersions());
        }
        pair(a, "stats", o.stats() == null ? "{}" : o.stats());
        return a;
    }

    // ---------------------------------------------------------------- the reaper

    /** Runs whose lease ran out: pending again for another attempt, or failed when attempts are used up. */
    public List<Reaped> reap(int most, Duration backoffBase, Duration backoffCap) {
        long now = now(connection().sync());
        List<String> ids = due(connection().sync(), LEASES, now, false, most);
        List<Reaped> reaped = new ArrayList<>();
        for (String id : ids) {
            Reaped r = reapOne(id, backoffBase.toMillis(), backoffCap.toMillis());
            if (r != null) {
                reaped.add(r);
            }
        }
        return reaped;
    }

    private Reaped reapOne(String id, long base, long cap) {
        String rk = runKey(id);
        return optimistic(c -> {
            c.watch(rk);
            Map<String, String> h = c.hgetall(rk);
            long now = now(c);
            if (!"RUNNING".equals(h.get("status"))) {
                // The run is not running any more: only the entry is left.
                return new Verdict<>(null, w -> {
                    w.zrem(LEASES, id);
                    w.zrem(DEADLINES, id);
                });
            }
            if (h.get("lease_ms") != null && Long.parseLong(h.get("lease_ms")) >= now) {
                return Verdict.of(null); // renewed since it was listed
            }
            String flow = h.get("flow_id");
            int attempt = Integer.parseInt(h.get("attempt"));
            boolean preview = "1".equals(h.get("preview"));
            boolean requeue = attempt < Integer.parseInt(h.get("max_attempts")) && !preview;
            long wait = jittered(Duration.ofMillis(Math.min(base * (1L << Math.min(attempt - 1, 20)), cap))).toMillis();
            String score = h.get("score");
            Double hint = preview ? null : readyScore(c, flow, requeue && wait == 0 ? Double.parseDouble(score) : null);
            return new Verdict<>(new Reaped(UUID.fromString(id), requeue, attempt), w -> {
                w.zrem(LEASES, id);
                w.zrem(DEADLINES, id);
                if (!preview) {
                    w.srem(runningKey(flow), id);
                }
                w.hdel(rk, "owner", "lease_ms");
                if (requeue) {
                    w.hset(rk, Map.of("status", "PENDING", "attempt", String.valueOf(attempt + 1),
                            "not_before_ms", String.valueOf(now + wait), "error_code", "LEASE_LOST",
                            "error", "The instance running attempt " + attempt + " stopped renewing its lease."));
                    w.hdel(rk, "started_ms");
                    makePending(w, flow, id, score, now + wait, now);
                    w.sadd(retryingKey(flow), id);
                } else {
                    w.hset(rk, Map.of("status", "FAILED", "error_code", "LEASE_LOST",
                            "error", "The instance running the last attempt stopped renewing its lease.",
                            "finished_ms", String.valueOf(now), "failure_event_owed", preview ? "0" : "1"));
                    if (!preview) {
                        w.sadd(OWED, id);
                    }
                    ended(w, flow, id, "FAILED", "LEASE_LOST", preview, h.get("window_start_ms"), now);
                }
                if (hint != null) {
                    w.zadd(READY, hint, flow);
                }
            });
        });
    }

    /** Asks the runs past their timeout and grace to stop; how many. */
    public int cancelOverdue(int most) {
        var sync = connection().sync();
        long now = now(sync);
        int asked = 0;
        for (String id : due(sync, DEADLINES, now, false, most)) {
            String rk = runKey(id);
            boolean did = optimistic(c -> {
                c.watch(rk);
                Map<String, String> h = c.hgetall(rk);
                boolean ask = "RUNNING".equals(h.get("status")) && !"1".equals(h.get("cancel_requested"));
                return new Verdict<>(ask, w -> {
                    w.zrem(DEADLINES, id);
                    if (ask) {
                        w.hset(rk, "cancel_requested", "1");
                    }
                });
            });
            if (did) {
                asked++;
            }
        }
        return asked;
    }

    /** Moves the runs whose back-off is over into their flows' queues. */
    private void promote() {
        var sync = connection().sync();
        long now = now(sync);
        for (String id : due(sync, DELAYED, now, true, 100)) {
            String rk = runKey(id);
            optimistic(c -> {
                c.watch(rk);
                Map<String, String> h = c.hgetall(rk);
                String flow = h.get("flow_id");
                if (!"PENDING".equals(h.get("status"))) {
                    return new Verdict<>(null, w -> {
                        w.zrem(DELAYED, id);
                        if (flow != null) {
                            w.zrem(delayedKey(flow), id);
                        }
                    });
                }
                if (Long.parseLong(h.get("not_before_ms")) > now) {
                    return Verdict.of(null);
                }
                double score = Double.parseDouble(h.get("score"));
                Double hint = readyScore(c, flow, score);
                return new Verdict<>(null, w -> {
                    w.zrem(DELAYED, id);
                    w.zrem(delayedKey(flow), id);
                    w.zadd(queueKey(flow), score, id);
                    w.zadd(READY, hint, flow);
                });
            });
        }
    }

    // ---------------------------------------------------------------- cancelling

    /** Ends a run that is still pending; false if it is not. */
    public boolean cancelPending(UUID uuid, String code, String text) {
        String id = uuid.toString();
        String rk = runKey(id);
        return optimistic(c -> {
            c.watch(rk);
            Map<String, String> h = c.hgetall(rk);
            if (!"PENDING".equals(h.get("status"))) {
                return Verdict.of(false);
            }
            String flow = h.get("flow_id");
            boolean preview = "1".equals(h.get("preview"));
            long now = now(c);
            return new Verdict<>(true, w -> {
                w.hset(rk, Map.of("status", "CANCELLED", "error_code", code, "error", text,
                        "finished_ms", String.valueOf(now)));
                if (!preview) {
                    w.zrem(queueKey(flow), id);
                    w.zrem(delayedKey(flow), id);
                    w.zrem(DELAYED, id);
                    w.srem(retryingKey(flow), id);
                }
                ended(w, flow, id, "CANCELLED", code, preview, null, now);
            });
        });
    }

    /** Asks a running run to stop, which its owner sees at the next heartbeat. */
    public boolean requestCancel(UUID uuid) {
        String rk = runKey(uuid.toString());
        return optimistic(c -> {
            c.watch(rk);
            if (!"RUNNING".equals(c.hget(rk, "status"))) {
                return Verdict.of(false);
            }
            return new Verdict<>(true, w -> w.hset(rk, "cancel_requested", "1"));
        });
    }

    // ---------------------------------------------------------------- flows

    /** Tells dispatch what it needs of a flow; called when a flow is deployed or changed, and every sweep. */
    public void syncFlow(FlowLimits f) {
        String flow = String.valueOf(f.flowId());
        var sync = connection().sync();
        sync.hset(metaKey(flow), Map.of("external_id", f.externalId(), "data_set_id", String.valueOf(f.dataSetId()),
                "enabled", f.enabled() ? "1" : "0", "deployed", f.deployed() ? "1" : "0",
                "max_concurrent", String.valueOf(f.maxConcurrentRuns())));
        // The limit may have risen, or the flow been switched off, which leaves runs to cancel.
        Double hint = readyScore(sync, flow, null);
        if (hint != null) {
            sync.zadd(READY, hint, flow);
        }
    }

    /** Forgets a deleted flow's pending and running runs. */
    public int purgeFlow(long flowId) {
        String flow = String.valueOf(flowId);
        var sync = connection().sync();
        Set<String> ids = new HashSet<>(sync.zrange(queueKey(flow), 0, -1));
        ids.addAll(sync.zrange(delayedKey(flow), 0, -1));
        ids.addAll(sync.smembers(runningKey(flow)));
        for (String id : ids) {
            // One transaction per run, so a run is forgotten whole. A claim racing it watches the run
            // and looks again at a queue without it.
            optimistic(c -> {
                c.watch(runKey(id));
                Map<String, String> h = c.hgetall(runKey(id));
                return new Verdict<>(null, w -> {
                    for (int attempt = 1; attempt <= attempts(h); attempt++) {
                        w.del(AgentTranscripts.key(UUID.fromString(id), attempt));
                    }
                    w.del(runKey(id));
                    w.zrem(LEASES, id);
                    w.zrem(DEADLINES, id);
                    w.zrem(UNARCHIVED, id);
                    w.zrem(LIVE, id);
                    w.zrem(DELAYED, id);
                    w.srem(OWED, id);
                });
            });
        }
        sync.del(queueKey(flow), delayedKey(flow), runningKey(flow), retryingKey(flow), metaKey(flow),
                lastKey(flow), failedWindowsKey(flow), scheduleKey(flow), scheduleLockKey(flowId));
        sync.zrem(READY, flow);
        sync.zrem(SCHEDULES, flow);
        return ids.size();
    }

    // ---------------------------------------------------------------- schedules

    /**
     * Gives the ticker a flow's schedule. A flow the run store has no schedule of, or has one of an
     * earlier deployment, starts at {@code firstTick}; one it has of this deployment keeps the tick it has
     * reached, which is ahead of anything Postgres was last told.
     */
    public void syncSchedule(ScheduleSpec spec) {
        String flow = String.valueOf(spec.flowId());
        String key = scheduleKey(flow);
        optimistic(c -> {
            c.watch(key);
            Map<String, String> h = c.hgetall(key);
            String deployed = String.valueOf(micros(spec.deployedAt()));
            boolean kept = deployed.equals(h.get("deployed_us")) && h.get("next_tick_ms") != null;
            String next = kept ? h.get("next_tick_ms") : String.valueOf(spec.firstTick().toEpochMilli());
            Map<String, String> fields = new LinkedHashMap<>();
            pair(fields, "external_id", spec.externalId());
            pair(fields, "data_set_id", spec.dataSetId());
            pair(fields, "version", spec.version());
            pair(fields, "max_attempts", spec.maxAttempts());
            pair(fields, "timeout_ms", spec.timeout().toMillis());
            pair(fields, "run_as", spec.runAs());
            pair(fields, "trigger", spec.trigger());
            pair(fields, "delay_ms", spec.delay().toMillis());
            pair(fields, "deployed_us", deployed);
            pair(fields, "next_tick_ms", next);
            double due = next.isEmpty() ? NEVER : Long.parseLong(next) + spec.delay().toMillis();
            return new Verdict<>(null, w -> {
                w.hset(key, fields);
                w.zadd(SCHEDULES, due, flow);
            });
        });
    }

    /** The flow has no schedule to make runs from: it is switched off, manual, or its identity was refused. */
    public void unschedule(long flowId) {
        String flow = String.valueOf(flowId);
        optimistic(c -> new Verdict<>(null, w -> {
            w.del(scheduleKey(flow));
            w.zrem(SCHEDULES, flow);
        }));
    }

    /** The flows whose next tick is due by the run store's clock, the most overdue first. */
    public List<Long> dueSchedules(int most) {
        var sync = connection().sync();
        return due(sync, SCHEDULES, now(sync), true, most).stream().map(Long::valueOf).toList();
    }

    /** Every flow with a schedule, finished ones too. */
    public List<Long> scheduledFlows() {
        return connection().sync().zrange(SCHEDULES, 0, -1).stream().map(Long::valueOf).toList();
    }

    public Optional<ScheduleRecord> schedule(long flowId) {
        Map<String, String> h = connection().sync().hgetall(scheduleKey(String.valueOf(flowId)));
        return h.isEmpty() ? Optional.empty() : Optional.of(scheduleRecord(flowId, h));
    }

    /** The schedules of these flows, those that have none left out. */
    public List<ScheduleRecord> schedules(Collection<Long> flowIds) {
        var async = connection().async();
        List<Long> ids = new ArrayList<>(flowIds);
        List<RedisFuture<Map<String, String>>> futures = new ArrayList<>();
        for (long id : ids) {
            futures.add(async.hgetall(scheduleKey(String.valueOf(id))));
        }
        List<ScheduleRecord> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Map<String, String> h = await(futures.get(i));
            if (!h.isEmpty()) {
                out.add(scheduleRecord(ids.get(i), h));
            }
        }
        return out;
    }

    /**
     * Moves a schedule on, if it is still waiting for {@code expected}: false when another instance moved
     * it first. The ticks up to {@code next} must have been made into runs before this is called, so that
     * a crash in between repeats ticks, which are not made twice, and never skips one.
     *
     * @param next     the tick to wait for now; null when the cron expression has none left
     * @param lastTick the latest tick made into a run by this move, null if none
     */
    public boolean advanceSchedule(long flowId, Instant expected, Instant next, Instant lastTick) {
        String flow = String.valueOf(flowId);
        String key = scheduleKey(flow);
        return optimistic(c -> {
            c.watch(key);
            Map<String, String> h = c.hgetall(key);
            if (!String.valueOf(expected.toEpochMilli()).equals(h.get("next_tick_ms"))) {
                return Verdict.of(false);
            }
            long delay = Long.parseLong(h.get("delay_ms"));
            return new Verdict<>(true, w -> {
                w.hset(key, "next_tick_ms", next == null ? "" : String.valueOf(next.toEpochMilli()));
                if (lastTick != null) {
                    w.hset(key, "last_tick_ms", String.valueOf(lastTick.toEpochMilli()));
                }
                w.zadd(SCHEDULES, next == null ? NEVER : next.toEpochMilli() + delay, flow);
            });
        });
    }

    /**
     * Takes the right to make a flow's ticks for {@code ttl}, so that the instances ticking at once take
     * different flows. It saves work and nothing else: a tick made twice is the same run, and a schedule
     * is moved on only from the tick it was seen at. An instance that dies holding it frees it by time.
     */
    public boolean lockSchedule(long flowId, String owner, Duration ttl) {
        return connection().sync().set(scheduleLockKey(flowId), owner, SetArgs.Builder.nx().px(ttl.toMillis())) != null;
    }

    public void unlockSchedule(long flowId, String owner) {
        String key = scheduleLockKey(flowId);
        optimistic(c -> {
            c.watch(key);
            return owner.equals(c.get(key)) ? new Verdict<>(null, w -> w.del(key)) : Verdict.of(null);
        });
    }

    private static ScheduleRecord scheduleRecord(long flowId, Map<String, String> h) {
        return new ScheduleRecord(flowId, str(h, "external_id"), Long.parseLong(h.get("data_set_id")),
                Integer.parseInt(h.get("version")), Integer.parseInt(h.get("max_attempts")),
                Duration.ofMillis(Long.parseLong(h.get("timeout_ms"))), str(h, "run_as"), str(h, "trigger"),
                Duration.ofMillis(Long.parseLong(h.get("delay_ms"))),
                Instant.ofEpochSecond(0, Long.parseLong(h.get("deployed_us")) * 1000),
                instant(h, "next_tick_ms"), instant(h, "last_tick_ms"));
    }

    /** Microseconds since the epoch, which is what Postgres keeps of a time. */
    private static long micros(Instant at) {
        return at.getEpochSecond() * 1_000_000 + at.getNano() / 1000;
    }

    // ---------------------------------------------------------------- reading

    public Optional<StoredRun> find(UUID id) {
        Map<String, String> h = connection().sync().hgetall(runKey(id.toString()));
        return h.isEmpty() ? Optional.empty() : Optional.of(stored(h));
    }

    /** The stored runs with these ids; those that are gone are left out. */
    public List<StoredRun> find(Collection<UUID> ids) {
        return hashes(ids.stream().map(UUID::toString).toList()).stream().map(RunStore::stored).toList();
    }

    /** The runs not yet ended, newest first: what the run list shows before the history. */
    public List<StoredRun> liveNewest(int most) {
        List<String> ids = connection().sync().zrevrange(LIVE, 0, most - 1);
        return hashes(ids).stream().map(RunStore::stored).toList();
    }

    /** A flow's runs in hand: pending first, in the order they start, then running. */
    public List<UUID> liveRunIds(long flowId, int most) {
        String flow = String.valueOf(flowId);
        List<UUID> ids = new ArrayList<>();
        var sync = connection().sync();
        sync.zrange(queueKey(flow), 0, most - 1).forEach(id -> ids.add(UUID.fromString(id)));
        if (ids.size() < most) {
            sync.zrange(delayedKey(flow), 0, most - ids.size() - 1).forEach(id -> ids.add(UUID.fromString(id)));
        }
        if (ids.size() < most) {
            sync.smembers(runningKey(flow)).stream().limit(most - ids.size())
                    .forEach(id -> ids.add(UUID.fromString(id)));
        }
        return ids;
    }

    /** What each flow has in hand, for the overview. */
    public Map<Long, FlowState> states(Collection<Long> flowIds) {
        var async = connection().async();
        Map<Long, Object[]> pending = new LinkedHashMap<>();
        for (long id : flowIds) {
            String flow = String.valueOf(id);
            pending.put(id, new Object[]{async.scard(runningKey(flow)), async.zcard(queueKey(flow)),
                    async.zcard(delayedKey(flow)), async.zrange(queueKey(flow), 0, 0),
                    async.srandmember(runningKey(flow), 20), async.hgetall(lastKey(flow)),
                    async.scard(retryingKey(flow))});
        }
        Map<Long, FlowState> out = new LinkedHashMap<>();
        for (var entry : pending.entrySet()) {
            Object[] f = entry.getValue();
            long running = await(f[0]);
            long queued = await(f[1]);
            long delayed = await(f[2]);
            List<String> head = await(f[3]);
            List<String> sample = await(f[4]);
            Map<String, String> last = await(f[5]);
            long retrying = await(f[6]);
            Instant oldest = null;
            if (!head.isEmpty()) {
                String created = connection().sync().hget(runKey(head.get(0)), "created_ms");
                oldest = created == null ? null : Instant.ofEpochMilli(Long.parseLong(created));
            }
            java.util.TreeSet<String> owners = new java.util.TreeSet<>();
            Instant since = null;
            for (Map<String, String> h : hashes(sample)) {
                if (h.get("owner") != null) {
                    owners.add(h.get("owner"));
                }
                if (h.get("started_ms") != null) {
                    Instant started = Instant.ofEpochMilli(Long.parseLong(h.get("started_ms")));
                    since = since == null || started.isBefore(since) ? started : since;
                }
            }
            LastRun lastRun = last.isEmpty() ? null : new LastRun(uuid(last, "id"), last.get("status"),
                    str(last, "error_code"), Instant.ofEpochMilli(Long.parseLong(last.get("finished_ms"))));
            out.put(entry.getKey(), new FlowState(entry.getKey(), (int) running, (int) (queued + delayed),
                    (int) retrying, oldest, owners.isEmpty() ? null : String.join(", ", owners), since, lastRun));
        }
        return out;
    }

    /** Whether the window ended in a failure that no run has put right since. */
    public boolean failedWindow(long flowId, Instant windowStart) {
        return connection().sync().sismember(failedWindowsKey(String.valueOf(flowId)),
                String.valueOf(windowStart.toEpochMilli()));
    }

    // ---------------------------------------------------------------- lifecycle events owed

    /** Runs that owe their lifecycle event, oldest first. */
    public List<StoredRun> owedEvents(int most) {
        List<String> ids = connection().sync().srandmember(OWED, most);
        return hashes(ids).stream().map(RunStore::stored).toList();
    }

    /**
     * The run's lifecycle event is written, or can never be: it no longer owes it, and once ClickHouse
     * has the run too it may expire. {@link #archived} and this each note what they did on the run and
     * look at what the other noted, in a transaction on the run, so whichever comes second expires it.
     */
    public void eventPaid(UUID uuid) {
        String id = uuid.toString();
        String rk = runKey(id);
        optimistic(c -> {
            c.watch(rk);
            Map<String, String> h = c.hgetall(rk);
            return new Verdict<>(null, w -> {
                w.srem(OWED, id);
                if (!h.isEmpty()) {
                    w.hset(rk, "failure_event_owed", "0");
                    if ("1".equals(h.get("archived"))) {
                        expireWithTranscripts(w, rk, uuid, h);
                    }
                }
            });
        });
    }

    // ---------------------------------------------------------------- history

    /**
     * Takes finished runs that are not in ClickHouse yet, oldest first, for this caller to archive:
     * they are hidden from other callers for {@code hide}, and come back if this one never says they
     * are archived. A run is this caller's if it set the run's token first; it then also moves the run
     * out of the way of the others' reads.
     */
    public List<StoredRun> takeUnarchived(int most, Duration hide) {
        var sync = connection().sync();
        long now = now(sync);
        List<String> won = new ArrayList<>();
        for (String id : due(sync, UNARCHIVED, now, true, most)) {
            if (sync.set("flow:archiving:" + id, "1", SetArgs.Builder.nx().px(hide.toMillis())) != null) {
                // XX: a run archived meanwhile is not put back.
                sync.zadd(UNARCHIVED, ZAddArgs.Builder.xx(), now + hide.toMillis(), id);
                won.add(id);
            }
        }
        return hashes(won).stream().map(RunStore::stored).toList();
    }

    /** ClickHouse has these: they expire here after the finished TTL. */
    public void archived(Collection<UUID> ids) {
        for (UUID uuid : ids) {
            String id = uuid.toString();
            String rk = runKey(id);
            optimistic(c -> {
                c.watch(rk);
                Map<String, String> h = c.hgetall(rk);
                return new Verdict<>(null, w -> {
                    w.zrem(UNARCHIVED, id);
                    if (!h.isEmpty()) {
                        w.hset(rk, "archived", "1");
                        // A run that still owes its lifecycle event is kept until that is written.
                        if (!"1".equals(h.get("failure_event_owed"))) {
                            expireWithTranscripts(w, rk, uuid, h);
                        }
                    }
                });
            });
        }
    }

    /** The run's hash and the transcript list of each of its attempts go after the finished TTL. */
    private void expireWithTranscripts(RedisAsyncCommands<String, String> w, String rk, UUID id,
                                       Map<String, String> h) {
        w.expire(rk, finishedTtl.toSeconds());
        for (int attempt = 1; attempt <= attempts(h); attempt++) {
            w.expire(AgentTranscripts.key(id, attempt), finishedTtl.toSeconds());
        }
    }

    /** The attempts a run has had, from its hash; one when the hash does not say. */
    private static int attempts(Map<String, String> h) {
        Integer attempt = intOrNull(h, "attempt");
        return attempt == null ? 1 : attempt;
    }

    // ---------------------------------------------------------------- the indexes

    /** Where a pending run waits: the flow's queue, or its back-off set until its time. */
    private static void makePending(RedisAsyncCommands<String, String> w, String flow, String id, String score,
                                    long notBefore, long now) {
        if (notBefore > now) {
            w.zadd(delayedKey(flow), notBefore, id);
            w.zadd(DELAYED, notBefore, id);
        } else {
            w.zadd(queueKey(flow), Double.parseDouble(score), id);
        }
    }

    /** A run that ended: waits to be archived, and is the flow's latest, and failed windows are kept. */
    private static void ended(RedisAsyncCommands<String, String> w, String flow, String id, String status,
                              String errorCode, boolean preview, String windowStart, long now) {
        w.zrem(LIVE, id);
        w.zadd(UNARCHIVED, now, id);
        if (preview) {
            return;
        }
        if ("FAILED".equals(status) && windowStart != null) {
            w.sadd(failedWindowsKey(flow), windowStart);
        } else if ("SUCCEEDED".equals(status) && windowStart != null) {
            w.srem(failedWindowsKey(flow), windowStart);
        }
        if ("FAILED".equals(status) || "SUCCEEDED".equals(status)) {
            w.hset(lastKey(flow), Map.of("id", id, "status", status, "error_code", errorCode == null ? "" : errorCode,
                    "finished_ms", String.valueOf(now)));
        }
    }

    /**
     * Where the flow goes in {@code flow:ready} for a run about to join its queue (or, with none, for a
     * room just opened): the earliest of that run and the queue's first. Null when the queue has none.
     */
    private static Double readyScore(RedisCommands<String, String> c, String flow, Double joining) {
        List<ScoredValue<String>> head = c.zrangeWithScores(queueKey(flow), 0, 0);
        Double first = head.isEmpty() ? null : head.get(0).getScore();
        if (first == null) {
            return joining;
        }
        return joining == null ? first : Double.valueOf(Math.min(first, joining));
    }

    /**
     * Takes a flow with nothing to start out of {@code flow:ready}, then looks again, so that what was
     * enqueued or freed between the claim's look and the removal puts it back.
     */
    private void settleReady(String flow) {
        var sync = connection().sync();
        sync.zrem(READY, flow);
        Map<String, String> meta = sync.hgetall(metaKey(flow));
        Double hint = readyScore(sync, flow, null);
        if (hint != null && (!usable(meta) || sync.scard(runningKey(flow)) < limit(meta))) {
            sync.zadd(READY, hint, flow);
        }
    }

    /** A flow nobody has described yet counts as on, with a limit of one. */
    private static boolean usable(Map<String, String> meta) {
        return !"0".equals(meta.get("enabled")) && !"0".equals(meta.get("deployed"));
    }

    private static int limit(Map<String, String> meta) {
        String v = meta.get("max_concurrent");
        return v == null ? 1 : Integer.parseInt(v);
    }

    // ---------------------------------------------------------------- plumbing

    /** The first {@code most} members scored up to {@code now}, or before it, earliest first. */
    private static List<String> due(RedisCommands<String, String> c, String key, long now, boolean includingNow,
                                    int most) {
        Range.Boundary<Long> to = includingNow ? Range.Boundary.including(now) : Range.Boundary.excluding(now);
        return c.zrangebyscore(key, Range.from(Range.Boundary.unbounded(), to), Limit.create(0, most));
    }

    private String tenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant on this thread; the run store belongs to one.");
        }
        return tenantId;
    }

    private StatefulRedisConnection<String, String> connection() {
        return kvrocks.connection(tenantId());
    }

    /**
     * Runs {@code attempt} on a connection of its own until its writes go through. The writes are
     * sent in one go, {@code MULTI} to {@code EXEC}, and are discarded if a watched key was written
     * since; the attempt then reads again, after a short random wait so that those who collided do not
     * again.
     */
    private <T> T optimistic(Attempt<T> attempt) {
        return kvrocks.dedicated(tenantId(), connection -> {
            RedisCommands<String, String> sync = connection.sync();
            for (int i = 0; i < ATTEMPTS; i++) {
                Verdict<T> verdict = attempt.read(sync);
                if (verdict.writes() == null) {
                    sync.unwatch();
                    return verdict.answer();
                }
                if (commit(connection, verdict.writes())) {
                    return verdict.answer();
                }
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(1, 4L * (i + 2)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted", e);
                }
            }
            throw new Contended("A change of a run was discarded " + ATTEMPTS + " times in a row; it is not made.");
        });
    }

    /** Sends the writes as one transaction; whether it went through. */
    private static boolean commit(StatefulRedisConnection<String, String> connection,
                                  Consumer<RedisAsyncCommands<String, String>> writes) {
        RedisAsyncCommands<String, String> async = connection.async();
        RedisFuture<TransactionResult> result;
        connection.setAutoFlushCommands(false);
        try {
            async.multi();
            writes.accept(async);
            result = async.exec();
            connection.flushCommands();
        } finally {
            connection.setAutoFlushCommands(true);
        }
        return !RunStore.<TransactionResult>await(result).wasDiscarded();
    }

    private static String runKey(String id) {
        return "flow:run:" + id;
    }

    private static String queueKey(String flow) {
        return "flow:q:" + flow;
    }

    private static String delayedKey(String flow) {
        return "flow:d:" + flow;
    }

    private static String metaKey(String flow) {
        return "flow:meta:" + flow;
    }

    private static String runningKey(String flow) {
        return "flow:running:" + flow;
    }

    private static String retryingKey(String flow) {
        return "flow:retrying:" + flow;
    }

    private static String lastKey(String flow) {
        return "flow:last:" + flow;
    }

    private static String scheduleKey(String flow) {
        return "flow:sched:" + flow;
    }

    private static String scheduleLockKey(long flow) {
        return "flow:sched-lock:" + flow;
    }

    private static String failedWindowsKey(String flow) {
        return "flow:failed-windows:" + flow;
    }

    private List<Map<String, String>> hashes(List<String> ids) {
        var async = connection().async();
        List<RedisFuture<Map<String, String>>> futures = new ArrayList<>();
        for (String id : ids) {
            futures.add(async.hgetall(runKey(id)));
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (RedisFuture<Map<String, String>> future : futures) {
            Map<String, String> h = await(future);
            if (!h.isEmpty()) {
                out.add(h);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> T await(Object future) {
        try {
            return ((RedisFuture<T>) future).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("KVRocks failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for KVRocks", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("KVRocks did not answer in " + WAIT, e);
        }
    }

    private static void pair(Map<String, String> a, String field, Object value) {
        if (value != null) {
            a.put(field, String.valueOf(value));
        }
    }

    private static void window(Map<String, String> a, TimeWindow w) {
        if (w != null) {
            pair(a, "window_start_ms", w.start().toEpochMilli());
            pair(a, "window_end_ms", w.end().toEpochMilli());
        }
    }

    private static StoredRun stored(Map<String, String> h) {
        return new StoredRun(uuid(h, "id"), longOrNull(h, "flow_id"), str(h, "flow_external_id"),
                longOrNull(h, "data_set_id"), intOrNull(h, "version"), h.get("status"),
                Integer.parseInt(h.get("attempt")), Integer.parseInt(h.get("max_attempts")),
                instant(h, "not_before_ms"), "1".equals(h.get("cancel_requested")), "1".equals(h.get("preview")),
                "1".equals(h.get("failure_event_owed")), Duration.ofMillis(Long.parseLong(h.get("timeout_ms"))),
                str(h, "owner"), window(h), str(h, "parameters"), str(h, "processor_versions"),
                str(h, "definition"), str(h, "trigger"), str(h, "triggered_by"), str(h, "run_as"),
                instant(h, "created_ms"), instant(h, "started_ms"), instant(h, "finished_ms"),
                str(h, "error_code"), str(h, "error"), str(h, "stats"));
    }

    private static TimeWindow window(Map<String, String> h) {
        return h.get("window_start_ms") == null ? null : new TimeWindow(instant(h, "window_start_ms"),
                instant(h, "window_end_ms"));
    }

    private static String str(Map<String, String> h, String key) {
        String v = h.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    private static UUID uuid(Map<String, String> h, String key) {
        return UUID.fromString(h.get(key));
    }

    private static Long longOrNull(Map<String, String> h, String key) {
        String v = str(h, key);
        return v == null ? null : Long.parseLong(v);
    }

    private static Integer intOrNull(Map<String, String> h, String key) {
        String v = str(h, key);
        return v == null ? null : Integer.parseInt(v);
    }

    private static Instant instant(Map<String, String> h, String key) {
        String v = str(h, key);
        return v == null ? null : Instant.ofEpochMilli(Long.parseLong(v));
    }
}
