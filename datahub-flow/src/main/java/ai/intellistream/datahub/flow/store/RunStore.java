// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.tenant.TenantContext;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Where flow runs are tracked while they are queued, running or just finished: the current tenant's
 * KVRocks (RUN_STORE.md has the keys). Every change of a run's state is one Lua script, so a run and
 * the indexes around it move together or not at all, and the clock is the server's.
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
    private static final String COMMON = resource("common.lua");

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
    public record Claimed(UUID id, Long flowId, Integer version, String definition, int attempt,
                          int maxAttempts, Duration timeout, TimeWindow window, String parameters,
                          boolean preview, String runAs, Duration leaseLeft) {
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

    /** A flow's latest finished run. */
    public record LastRun(UUID id, String status, String errorCode, Instant finishedAt) {
    }

    /** What a flow has in hand now. */
    public record FlowState(long flowId, int running, int queued, int retrying, Instant oldestWaiting,
                            String runningOn, Instant runningSince, LastRun last) {
    }

    private record Script(String text, String sha) {
    }

    private final TenantKvrocks kvrocks;
    private final Duration finishedTtl;
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();

    public RunStore(TenantKvrocks kvrocks,
                    @Value("${datahub.flow.runs.finished-ttl:PT15M}") Duration finishedTtl) {
        this.kvrocks = kvrocks;
        this.finishedTtl = finishedTtl;
    }

    // ---------------------------------------------------------------- the clock

    /** The server's clock, which decides every lease and back-off. */
    public Instant now() {
        List<String> time = connection().sync().time();
        return Instant.ofEpochMilli(Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000);
    }

    // ---------------------------------------------------------------- making runs

    /** Makes the run pending; false when a run with this id exists already. */
    public boolean enqueue(NewRun run) {
        return integer("enqueue", enqueueArgs(run)) == 1;
    }

    /** Makes each run pending, a pipeline of enqueues; how many were new. */
    public int enqueueAll(List<NewRun> runs) {
        List<RedisFuture<?>> futures = new ArrayList<>();
        Script script = script("enqueue");
        StatefulRedisConnection<String, String> connection = connection();
        for (NewRun run : runs) {
            futures.add(connection.async().evalsha(script.sha(), ScriptOutputType.INTEGER, new String[0],
                    enqueueArgs(run)));
        }
        int made = 0;
        for (RedisFuture<?> future : futures) {
            try {
                made += ((Long) future.get(WAIT.toSeconds(), TimeUnit.SECONDS)).intValue();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RedisNoScriptException) {
                    // The server forgot the scripts (a restart): load them and do the whole batch again;
                    // enqueueing is idempotent, so what was made already is not made twice.
                    loadAll();
                    return enqueueAll(runs);
                }
                throw new IllegalStateException("Enqueueing runs failed", e.getCause());
            } catch (InterruptedException | TimeoutException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Enqueueing runs failed", e);
            }
        }
        return made;
    }

    private static String[] enqueueArgs(NewRun r) {
        List<String> a = new ArrayList<>(List.of(r.id().toString(), String.valueOf(r.flowId()), "0",
                r.window() == null ? "" : String.valueOf(r.window().start().toEpochMilli()),
                String.valueOf(r.notBefore() == null ? 0 : r.notBefore().toEpochMilli())));
        pair(a, "flow_external_id", r.flowExternalId());
        pair(a, "data_set_id", r.dataSetId());
        pair(a, "version", r.version());
        pair(a, "max_attempts", r.maxAttempts());
        pair(a, "timeout_ms", r.timeout().toMillis());
        window(a, r.window());
        pair(a, "parameters", r.parameters() == null ? "{}" : r.parameters());
        pair(a, "trigger", r.trigger());
        pair(a, "triggered_by", r.triggeredBy());
        pair(a, "run_as", r.runAs());
        return a.toArray(String[]::new);
    }

    /** Makes a preview, which is never queued: its instance takes it by id. */
    public void enqueuePreview(NewPreview p) {
        List<String> a = new ArrayList<>(List.of(p.id().toString(), p.flowId() == null ? "" : String.valueOf(p.flowId()),
                "1", p.window() == null ? "" : String.valueOf(p.window().start().toEpochMilli()), "0"));
        pair(a, "flow_external_id", p.flowExternalId());
        pair(a, "data_set_id", p.dataSetId());
        pair(a, "version", p.version());
        pair(a, "max_attempts", 1);
        pair(a, "timeout_ms", p.timeout().toMillis());
        window(a, p.window());
        pair(a, "parameters", p.parameters() == null ? "{}" : p.parameters());
        pair(a, "definition", p.definition());
        pair(a, "trigger", "preview");
        pair(a, "triggered_by", p.triggeredBy());
        pair(a, "run_as", p.triggeredBy());
        integer("enqueue", a.toArray(String[]::new));
    }

    // ---------------------------------------------------------------- starting and keeping runs

    /**
     * Takes up to {@code most} pending runs for {@code owner}: earliest windows first, each flow only
     * as many as its limit leaves room for, the runs of a flow switched off cancelled instead.
     */
    public List<Claimed> claim(String owner, Duration lease, int most, Duration grace) {
        List<Object> ids = list("claim", owner, String.valueOf(lease.toMillis()), String.valueOf(most),
                String.valueOf(grace.toMillis()));
        return claimed(ids.stream().map(Object::toString).toList(), lease);
    }

    /** Takes this one run; for a manual start, or a preview on the instance that made it. */
    public Claim claimOne(UUID id, String owner, Duration lease, Duration grace) {
        long result = integer("claim_one", id.toString(), owner, String.valueOf(lease.toMillis()),
                String.valueOf(grace.toMillis()));
        return result == 1 ? Claim.CLAIMED : result == 2 ? Claim.CANCELLED : Claim.NOT_CLAIMED;
    }

    /** The claimed run, read back; empty if it ended in the meantime. */
    public Optional<Claimed> claimedRun(UUID id, Duration lease) {
        return claimed(List.of(id.toString()), lease).stream().findFirst();
    }

    private List<Claimed> claimed(List<String> ids, Duration lease) {
        List<Claimed> out = new ArrayList<>();
        for (Map<String, String> h : hashes(ids)) {
            out.add(new Claimed(uuid(h, "id"), longOrNull(h, "flow_id"), intOrNull(h, "version"), str(h, "definition"),
                    Integer.parseInt(h.get("attempt")), Integer.parseInt(h.get("max_attempts")),
                    Duration.ofMillis(Long.parseLong(h.get("timeout_ms"))), window(h), str(h, "parameters"),
                    "1".equals(h.get("preview")), str(h, "run_as"), lease));
        }
        return out;
    }

    /** Renews the leases {@code owner} still holds; a run missing from the answer is no longer its. */
    public List<Renewed> renew(Collection<UUID> ids, String owner, Duration lease) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> a = new ArrayList<>(List.of(owner, String.valueOf(lease.toMillis())));
        ids.forEach(id -> a.add(id.toString()));
        List<Object> out = list("renew", a.toArray(String[]::new));
        List<Renewed> renewed = new ArrayList<>();
        for (int i = 0; i + 2 < out.size(); i += 3) {
            renewed.add(new Renewed(UUID.fromString(out.get(i).toString()), "1".equals(out.get(i + 1).toString()),
                    Duration.ofMillis(Long.parseLong(out.get(i + 2).toString()))));
        }
        return renewed;
    }

    // ---------------------------------------------------------------- ending attempts

    /** The run ended for good. False when this owner no longer holds the attempt. */
    public boolean complete(UUID id, String owner, int attempt, String status, boolean failureEventOwed,
                            Outcome outcome) {
        List<String> a = finishArgs(id, owner, attempt, "complete", status, Duration.ZERO, failureEventOwed);
        outcome(a, outcome, true);
        return integer("finish", a.toArray(String[]::new)) == 1;
    }

    /** The attempt failed and another is allowed: pending again after {@code backoff}. */
    public boolean retry(UUID id, String owner, int attempt, Duration backoff, Outcome outcome) {
        List<String> a = finishArgs(id, owner, attempt, "retry", "PENDING", backoff, false);
        outcome(a, outcome, false);
        return integer("finish", a.toArray(String[]::new)) == 1;
    }

    /** Hands the run back unchanged, as this instance shuts down: the attempt does not count. */
    public boolean release(UUID id, String owner, int attempt) {
        return integer("finish", finishArgs(id, owner, attempt, "release", "PENDING", Duration.ZERO, false)
                .toArray(String[]::new)) == 1;
    }

    private static List<String> finishArgs(UUID id, String owner, int attempt, String kind, String status,
                                           Duration backoff, boolean owed) {
        return new ArrayList<>(List.of(id.toString(), owner, String.valueOf(attempt), kind, status,
                String.valueOf(backoff.toMillis()), owed ? "1" : "0"));
    }

    private static void outcome(List<String> a, Outcome o, boolean withParameters) {
        pair(a, "error_code", o.errorCode() == null ? "" : o.errorCode());
        pair(a, "error", o.error() == null ? "" : o.error());
        if (withParameters && o.parameters() != null) {
            pair(a, "parameters", o.parameters());
        }
        if (o.processorVersions() != null) {
            pair(a, "processor_versions", o.processorVersions());
        }
        pair(a, "stats", o.stats() == null ? "{}" : o.stats());
    }

    // ---------------------------------------------------------------- the reaper

    /** Runs whose lease ran out: pending again for another attempt, or failed when attempts are used up. */
    public List<Reaped> reap(int most, Duration backoffBase, Duration backoffCap) {
        List<Object> out = list("reap", String.valueOf(most), String.valueOf(backoffBase.toMillis()),
                String.valueOf(backoffCap.toMillis()));
        List<Reaped> reaped = new ArrayList<>();
        for (int i = 0; i + 2 < out.size(); i += 3) {
            reaped.add(new Reaped(UUID.fromString(out.get(i).toString()), "REQUEUED".equals(out.get(i + 1).toString()),
                    Integer.parseInt(out.get(i + 2).toString())));
        }
        return reaped;
    }

    /** Asks the runs past their timeout and grace to stop; how many. */
    public int cancelOverdue(int most) {
        return (int) integer("sweep_deadlines", String.valueOf(most));
    }

    // ---------------------------------------------------------------- cancelling

    /** Ends a run that is still pending; false if it is not. */
    public boolean cancelPending(UUID id, String code, String text) {
        return integer("cancel", id.toString(), code, text) == 1;
    }

    /** Asks a running run to stop, which its owner sees at the next heartbeat. */
    public boolean requestCancel(UUID id) {
        return integer("request_cancel", id.toString()) == 1;
    }

    // ---------------------------------------------------------------- flows

    /** Tells dispatch what it needs of a flow; called when a flow is deployed or changed, and every sweep. */
    public void syncFlow(FlowLimits f) {
        integer("sync_flow", String.valueOf(f.flowId()), f.externalId(), String.valueOf(f.dataSetId()),
                f.enabled() ? "1" : "0", f.deployed() ? "1" : "0", String.valueOf(f.maxConcurrentRuns()));
    }

    /** Forgets a deleted flow's pending and running runs. */
    public int purgeFlow(long flowId) {
        return (int) integer("purge_flow", String.valueOf(flowId));
    }

    // ---------------------------------------------------------------- reading

    public Optional<StoredRun> find(UUID id) {
        Map<String, String> h = connection().sync().hgetall(runKey(id));
        return h.isEmpty() ? Optional.empty() : Optional.of(stored(h));
    }

    /** The stored runs with these ids; those that are gone are left out. */
    public List<StoredRun> find(Collection<UUID> ids) {
        return hashes(ids.stream().map(UUID::toString).toList()).stream().map(RunStore::stored).toList();
    }

    /** A flow's runs in hand: pending first, in the order they start, then running. */
    public List<UUID> liveRunIds(long flowId, int most) {
        List<UUID> ids = new ArrayList<>();
        var sync = connection().sync();
        sync.zrange("flow:q:" + flowId, 0, most - 1).forEach(id -> ids.add(UUID.fromString(id)));
        if (ids.size() < most) {
            sync.zrange("flow:d:" + flowId, 0, most - ids.size() - 1).forEach(id -> ids.add(UUID.fromString(id)));
        }
        if (ids.size() < most) {
            sync.smembers("flow:running:" + flowId).stream().limit(most - ids.size())
                    .forEach(id -> ids.add(UUID.fromString(id)));
        }
        return ids;
    }

    /** What each flow has in hand, for the overview. */
    public Map<Long, FlowState> states(Collection<Long> flowIds) {
        var async = connection().async();
        Map<Long, Object[]> pending = new LinkedHashMap<>();
        for (long flow : flowIds) {
            pending.put(flow, new Object[]{async.hgetall("flow:meta:" + flow), async.zcard("flow:q:" + flow),
                    async.zcard("flow:d:" + flow), async.zrange("flow:q:" + flow, 0, 0),
                    async.srandmember("flow:running:" + flow, 20), async.hgetall("flow:last:" + flow)});
        }
        Map<Long, FlowState> out = new LinkedHashMap<>();
        for (var entry : pending.entrySet()) {
            Object[] f = entry.getValue();
            Map<String, String> meta = await(f[0]);
            long queued = await(f[1]);
            long delayed = await(f[2]);
            List<String> head = await(f[3]);
            List<String> sample = await(f[4]);
            Map<String, String> last = await(f[5]);
            Instant oldest = null;
            if (!head.isEmpty()) {
                String created = connection().sync().hget("flow:run:" + head.get(0), "created_ms");
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
            out.put(entry.getKey(), new FlowState(entry.getKey(), (int) Long.parseLong(meta.getOrDefault("running", "0")),
                    (int) (queued + delayed), (int) Long.parseLong(meta.getOrDefault("retrying", "0")), oldest,
                    owners.isEmpty() ? null : String.join(", ", owners), since, lastRun));
        }
        return out;
    }

    /** Whether the window ended in a failure that no run has put right since. */
    public boolean failedWindow(long flowId, Instant windowStart) {
        return connection().sync().sismember("flow:failed-windows:" + flowId,
                String.valueOf(windowStart.toEpochMilli()));
    }

    // ---------------------------------------------------------------- lifecycle events owed

    /** Runs that owe their lifecycle event, oldest first. */
    public List<StoredRun> owedEvents(int most) {
        List<String> ids = connection().sync().srandmember("flow:owed", most);
        return hashes(ids).stream().map(RunStore::stored).toList();
    }

    /** The run's lifecycle event is written, or can never be. */
    public void eventPaid(UUID id) {
        integer("event_paid", String.valueOf(finishedTtl.toSeconds()), id.toString());
    }

    // ---------------------------------------------------------------- history

    /**
     * Takes finished runs that are not in ClickHouse yet, oldest first, for this caller to archive:
     * they are hidden from other callers for {@code hide}, and come back if this one never says they
     * are archived.
     */
    public List<StoredRun> takeUnarchived(int most, Duration hide) {
        List<Object> ids = list("take_unarchived", String.valueOf(most), String.valueOf(hide.toSeconds()));
        return hashes(ids.stream().map(Object::toString).toList()).stream().map(RunStore::stored).toList();
    }

    /** ClickHouse has these: they expire here after the finished TTL. */
    public void archived(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> a = new ArrayList<>(List.of(String.valueOf(finishedTtl.toSeconds())));
        ids.forEach(id -> a.add(id.toString()));
        integer("archived", a.toArray(String[]::new));
    }

    // ---------------------------------------------------------------- plumbing

    private StatefulRedisConnection<String, String> connection() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant on this thread; the run store belongs to one.");
        }
        return kvrocks.connection(tenantId);
    }

    private static String runKey(UUID id) {
        return "flow:run:" + id;
    }

    private long integer(String name, String... args) {
        return (Long) call(name, ScriptOutputType.INTEGER, args);
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(String name, String... args) {
        return (List<Object>) call(name, ScriptOutputType.MULTI, args);
    }

    private Object call(String name, ScriptOutputType type, String... args) {
        Script script = script(name);
        var sync = connection().sync();
        try {
            return sync.evalsha(script.sha(), type, new String[0], args);
        } catch (RedisNoScriptException e) {
            loadAll();
            return sync.evalsha(script.sha(), type, new String[0], args);
        }
    }

    private Script script(String name) {
        return scripts.computeIfAbsent(name, n -> {
            String text = COMMON + "\n" + resource(n + ".lua");
            try {
                byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) {
                    hex.append(String.format("%02x", b));
                }
                return new Script(text, hex.toString());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static final List<String> NAMES = List.of("enqueue", "claim", "claim_one", "renew", "finish", "reap",
            "cancel", "request_cancel", "take_unarchived", "sweep_deadlines", "sync_flow", "purge_flow", "archived", "event_paid");

    /** Loads every script into the tenant's server, which forgets them when it restarts. */
    private void loadAll() {
        var sync = connection().sync();
        for (String name : NAMES) {
            sync.scriptLoad(script(name).text());
        }
    }

    private List<Map<String, String>> hashes(List<String> ids) {
        var async = connection().async();
        List<RedisFuture<Map<String, String>>> futures = new ArrayList<>();
        for (String id : ids) {
            futures.add(async.hgetall("flow:run:" + id));
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

    private static void pair(List<String> a, String field, Object value) {
        if (value != null) {
            a.add(field);
            a.add(String.valueOf(value));
        }
    }

    private static void window(List<String> a, TimeWindow w) {
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

    private static String resource(String name) {
        try (var in = RunStore.class.getResourceAsStream("/runstore/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing script " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
