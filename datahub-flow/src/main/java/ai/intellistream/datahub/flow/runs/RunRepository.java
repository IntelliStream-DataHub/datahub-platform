// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.api.TimeWindow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The run protocol's statements against the tenant's {@code flow} schema (Appendix B of the plan).
 *
 * <p>Every lock is a row lock inside the caller's transaction ({@code FOR UPDATE}, mostly with
 * {@code SKIP LOCKED}), which is what works behind pgbouncer in transaction mode: no advisory locks,
 * no session state. Every time that matters — a lease's expiry, a retry's back-off, whether a tick
 * is due — is the database's {@code now()}, so no instance's clock decides it.
 *
 * <p>Writes that only the run's owner may make carry {@code owner = ? AND attempt = ? AND status =
 * 'RUNNING'}: an instance whose lease was taken can no longer change the run, whatever it believes.
 */
@Repository
public class RunRepository {

    private static final String RUN_COLUMNS = "r.id, r.flow_id, f.external_id AS flow_external_id, "
            + "f.data_set_id, r.version, r.status, r.attempt, r.max_attempts, r.not_before, "
            + "r.cancel_requested, r.preview, r.failure_event_owed, r.memory_bytes, "
            + "extract(epoch FROM r.run_timeout)::bigint AS timeout_seconds, r.owner, "
            + "r.window_start, r.window_end, r.parameters::text AS parameters, "
            + "r.processor_versions::text AS processor_versions, r.trigger, r.triggered_by, r.run_as, "
            + "r.created_at, r.started_at, r.finished_at, r.error_code, r.error, r.stats::text AS stats";

    private static final RowMapper<StoredRun> RUN = (rs, n) -> new StoredRun(
            rs.getObject("id", UUID.class), (Long) rs.getObject("flow_id"),
            rs.getString("flow_external_id"), (Long) rs.getObject("data_set_id"),
            (Integer) rs.getObject("version"), rs.getString("status"), rs.getInt("attempt"),
            rs.getInt("max_attempts"), instant(rs, "not_before"), rs.getBoolean("cancel_requested"),
            rs.getBoolean("preview"), rs.getBoolean("failure_event_owed"), rs.getLong("memory_bytes"),
            Duration.ofSeconds(rs.getLong("timeout_seconds")), rs.getString("owner"),
            window(rs), rs.getString("parameters"), rs.getString("processor_versions"),
            rs.getString("trigger"), rs.getString("triggered_by"), rs.getString("run_as"),
            instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
            rs.getString("error_code"), rs.getString("error"), rs.getString("stats"));

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A run as stored, with its flow's external id and dataset. */
    public record StoredRun(UUID id, Long flowId, String flowExternalId, Long dataSetId, Integer version,
                            String status, int attempt, int maxAttempts, Instant notBefore,
                            boolean cancelRequested, boolean preview, boolean failureEventOwed,
                            long memoryBytes, Duration timeout, String owner, TimeWindow window,
                            String parameters, String processorVersions, String trigger,
                            String triggeredBy, String runAs, Instant createdAt, Instant startedAt,
                            Instant finishedAt, String errorCode, String error, String stats) {
    }

    /** What a new run is created with. */
    public record NewRun(long flowId, int version, int maxAttempts, long memoryBytes, Duration timeout,
                         TimeWindow window, String parameters, String trigger, String triggeredBy,
                         String runAs) {
    }

    /** A deployed, enabled flow whose schedule is due, locked for this transaction. */
    public record DueFlow(long id, int version, String trigger, Instant nextTick, int maxAttempts,
                          long memoryBytes, Duration timeout, String runAs) {
    }

    /** A pending run that may be claimed, locked for this transaction. */
    public record Candidate(UUID id, Long flowId, long memoryBytes) {
    }

    /** What the claim needs to know of a run's flow, the row locked for this transaction. */
    public record FlowGate(long id, boolean enabled, Integer deployedVersion, int maxConcurrentRuns,
                           int running) {
    }

    /** A run this instance now owns, and how long its lease has left by the database's clock. */
    public record Claimed(UUID id, Long flowId, Integer version, String definition, int attempt,
                          int maxAttempts, long memoryBytes, Duration timeout, TimeWindow window,
                          String parameters, boolean preview, String runAs, Duration leaseLeft) {
    }

    /** A renewed lease. */
    public record Renewed(UUID id, boolean cancelRequested, Duration leaseLeft) {
    }

    /** A running run whose lease expired. */
    public record Expired(UUID id, int attempt, int maxAttempts, boolean preview) {
    }

    public Instant now() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    // ---------------------------------------------------------------- the sweep: schedules

    public List<DueFlow> lockDueSchedules(int limit) {
        return jdbc.query("SELECT id, deployed_version, trigger::text AS trigger, next_tick, "
                        + "max_attempts, memory_bytes, extract(epoch FROM run_timeout)::bigint AS timeout_seconds, "
                        + "coalesce(identity_subject, identity_client_name) AS run_as "
                        + "FROM flow.flow WHERE enabled AND next_due_at <= now() "
                        + "ORDER BY next_due_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new DueFlow(rs.getLong("id"), rs.getInt("deployed_version"),
                        rs.getString("trigger"), instant(rs, "next_tick"), rs.getInt("max_attempts"),
                        rs.getLong("memory_bytes"), Duration.ofSeconds(rs.getLong("timeout_seconds")),
                        rs.getString("run_as")),
                limit);
    }

    /** Moves a schedule on; a null {@code nextTick} means the cron expression has no more ticks. */
    public void advanceSchedule(long flowId, Instant nextTick, Instant nextDue, Instant lastTick) {
        jdbc.update("UPDATE flow.flow SET next_tick = ?, next_due_at = ?, "
                        + "last_tick = coalesce(?, last_tick) WHERE id = ?",
                timestamp(nextTick), timestamp(nextDue), timestamp(lastTick), flowId);
    }

    public UUID insert(NewRun run) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO flow.run (id, flow_id, version, status, attempt, max_attempts, "
                        + "memory_bytes, run_timeout, window_start, window_end, parameters, trigger, "
                        + "triggered_by, run_as) VALUES (?, ?, ?, 'PENDING', 1, ?, ?, "
                        + "make_interval(secs => ?), ?, ?, ?::jsonb, ?, ?, ?)",
                id, run.flowId(), run.version(), run.maxAttempts(), run.memoryBytes(),
                (double) run.timeout().toSeconds(),
                run.window() == null ? null : timestamp(run.window().start()),
                run.window() == null ? null : timestamp(run.window().end()),
                run.parameters() == null ? "{}" : run.parameters(), run.trigger(), run.triggeredBy(),
                run.runAs());
        return id;
    }

    // ---------------------------------------------------------------- the claim

    /** Pending runs that may start now, earliest window first; {@code only} narrows it to one run. */
    public List<Candidate> lockPending(int limit, UUID only) {
        String sql = "SELECT id, flow_id, memory_bytes FROM flow.run WHERE status = 'PENDING' "
                + "AND not_before <= now() AND NOT cancel_requested"
                + (only == null ? "" : " AND id = ?")
                + " ORDER BY window_start, created_at LIMIT ? FOR UPDATE SKIP LOCKED";
        RowMapper<Candidate> mapper = (rs, n) -> new Candidate(rs.getObject("id", UUID.class),
                (Long) rs.getObject("flow_id"), rs.getLong("memory_bytes"));
        return only == null ? jdbc.query(sql, mapper, limit) : jdbc.query(sql, mapper, only, limit);
    }

    /**
     * Locks the flows of the runs about to be claimed, in id order so that two instances claiming
     * runs of the same flows cannot deadlock, and counts each one's running runs. Holding the lock
     * is what makes {@code max_concurrent_runs} hold: two instances claiming two runs of one flow
     * would otherwise both count zero.
     */
    public Map<Long, FlowGate> lockFlows(Collection<Long> flowIds) {
        Map<Long, FlowGate> gates = new HashMap<>();
        if (flowIds.isEmpty()) {
            return gates;
        }
        Long[] ids = flowIds.toArray(Long[]::new);
        Map<Long, Integer> running = new HashMap<>();
        jdbc.query("SELECT id, enabled, deployed_version, max_concurrent_runs FROM flow.flow "
                        + "WHERE id = ANY (?) ORDER BY id FOR UPDATE",
                rs -> {
                    gates.put(rs.getLong("id"), new FlowGate(rs.getLong("id"), rs.getBoolean("enabled"),
                            (Integer) rs.getObject("deployed_version"), rs.getInt("max_concurrent_runs"), 0));
                }, (Object) ids);
        jdbc.query("SELECT flow_id, count(*) AS running FROM flow.run WHERE status = 'RUNNING' "
                        + "AND flow_id = ANY (?) GROUP BY flow_id",
                rs -> {
                    running.put(rs.getLong("flow_id"), rs.getInt("running"));
                }, (Object) ids);
        gates.replaceAll((id, g) -> new FlowGate(g.id(), g.enabled(), g.deployedVersion(),
                g.maxConcurrentRuns(), running.getOrDefault(id, 0)));
        return gates;
    }

    /** Takes a pending run: running, owned by {@code owner}, with a lease of {@code lease}. */
    public Optional<Claimed> claim(UUID id, String owner, Duration lease) {
        return jdbc.query("UPDATE flow.run SET status = 'RUNNING', owner = ?, "
                        + "lease_expires_at = now() + make_interval(secs => ?), started_at = now(), "
                        + "finished_at = NULL WHERE id = ? AND status = 'PENDING' RETURNING id, "
                        + "flow_id, version, definition::text AS definition, attempt, max_attempts, "
                        + "memory_bytes, extract(epoch FROM run_timeout)::bigint AS timeout_seconds, "
                        + "window_start, window_end, parameters::text AS parameters, preview, run_as, "
                        + "(extract(epoch FROM lease_expires_at - now()) * 1000)::bigint AS lease_ms",
                (rs, n) -> new Claimed(rs.getObject("id", UUID.class), (Long) rs.getObject("flow_id"),
                        (Integer) rs.getObject("version"), rs.getString("definition"),
                        rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getLong("memory_bytes"),
                        Duration.ofSeconds(rs.getLong("timeout_seconds")), window(rs),
                        rs.getString("parameters"), rs.getBoolean("preview"), rs.getString("run_as"),
                        Duration.ofMillis(rs.getLong("lease_ms"))),
                owner, (double) lease.toMillis() / 1000, id).stream().findFirst();
    }

    /** Ends a pending run that may no longer start, such as one of a flow switched off since. */
    public void cancelPending(UUID id, String code, String error) {
        jdbc.update("UPDATE flow.run SET status = 'CANCELLED', error_code = ?, error = ?, "
                + "finished_at = now() WHERE id = ? AND status = 'PENDING'", code, error, id);
    }

    // ---------------------------------------------------------------- leases

    /**
     * Renews the leases of {@code runs} that this instance still owns, in one statement, and says
     * how long each has left and whether someone asked to cancel it. A run missing from the answer
     * is no longer this instance's.
     */
    public List<Renewed> renew(Collection<UUID> runs, String owner, Duration lease) {
        if (runs.isEmpty()) {
            return List.of();
        }
        return jdbc.query("UPDATE flow.run SET lease_expires_at = now() + make_interval(secs => ?) "
                        + "WHERE id = ANY (?) AND owner = ? AND status = 'RUNNING' RETURNING id, "
                        + "cancel_requested, "
                        + "(extract(epoch FROM lease_expires_at - now()) * 1000)::bigint AS lease_ms",
                (rs, n) -> new Renewed(rs.getObject("id", UUID.class), rs.getBoolean("cancel_requested"),
                        Duration.ofMillis(rs.getLong("lease_ms"))),
                (double) lease.toMillis() / 1000, runs.toArray(UUID[]::new), owner);
    }

    // ---------------------------------------------------------------- finishing

    /** What an attempt leaves on the row however it ended. */
    public record Outcome(String errorCode, String error, String parameters, String processorVersions,
                          String stats) {
    }

    /** The run ended for good: SUCCEEDED, FAILED or CANCELLED. False when this instance no longer owns it. */
    public boolean complete(UUID id, String owner, int attempt, String status, boolean failureEventOwed,
                            Outcome outcome) {
        return jdbc.update("UPDATE flow.run SET status = ?, failure_event_owed = ?, error_code = ?, "
                        + "error = ?, parameters = coalesce(?::jsonb, parameters), "
                        + "processor_versions = coalesce(?::jsonb, processor_versions), "
                        + "stats = ?::jsonb, finished_at = now(), owner = NULL, lease_expires_at = NULL "
                        + "WHERE id = ? AND owner = ? AND attempt = ? AND status = 'RUNNING'",
                status, failureEventOwed, outcome.errorCode(), outcome.error(), outcome.parameters(),
                outcome.processorVersions(), outcome.stats(), id, owner, attempt) == 1;
    }

    /** The attempt failed and another is allowed: pending again, not before {@code backoff}. */
    public boolean retry(UUID id, String owner, int attempt, Duration backoff, Outcome outcome) {
        return jdbc.update("UPDATE flow.run SET status = 'PENDING', attempt = attempt + 1, "
                        + "not_before = now() + make_interval(secs => ?), error_code = ?, error = ?, "
                        + "processor_versions = coalesce(?::jsonb, processor_versions), "
                        + "stats = ?::jsonb, owner = NULL, lease_expires_at = NULL "
                        + "WHERE id = ? AND owner = ? AND attempt = ? AND status = 'RUNNING'",
                (double) backoff.toSeconds(), outcome.errorCode(), outcome.error(),
                outcome.processorVersions(), outcome.stats(), id, owner, attempt) == 1;
    }

    /** Hands a run back unchanged, as this instance shuts down: the attempt does not count. */
    public boolean release(UUID id, String owner, int attempt) {
        return jdbc.update("UPDATE flow.run SET status = 'PENDING', owner = NULL, "
                        + "lease_expires_at = NULL, started_at = NULL "
                        + "WHERE id = ? AND owner = ? AND attempt = ? AND status = 'RUNNING'",
                id, owner, attempt) == 1;
    }

    // ---------------------------------------------------------------- the reaper

    public List<Expired> lockExpired(int limit) {
        return jdbc.query("SELECT id, attempt, max_attempts, preview FROM flow.run "
                        + "WHERE status = 'RUNNING' AND lease_expires_at < now() "
                        + "LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new Expired(rs.getObject("id", UUID.class), rs.getInt("attempt"),
                        rs.getInt("max_attempts"), rs.getBoolean("preview")),
                limit);
    }

    /** An expired run with attempts left goes back to pending, as its next attempt. */
    public void requeueExpired(UUID id, int attempt, Duration backoff, String error) {
        jdbc.update("UPDATE flow.run SET status = 'PENDING', attempt = attempt + 1, owner = NULL, "
                        + "lease_expires_at = NULL, not_before = now() + make_interval(secs => ?), "
                        + "error_code = 'LEASE_LOST', error = ? "
                        + "WHERE id = ? AND attempt = ? AND status = 'RUNNING'",
                (double) backoff.toSeconds(), error, id, attempt);
    }

    /** An expired run on its last attempt has failed; its failure event is owed (not for a preview). */
    public void failExpired(UUID id, int attempt, boolean preview, String error) {
        jdbc.update("UPDATE flow.run SET status = 'FAILED', owner = NULL, lease_expires_at = NULL, "
                        + "error_code = 'LEASE_LOST', error = ?, failure_event_owed = ?, "
                        + "finished_at = now() WHERE id = ? AND attempt = ? AND status = 'RUNNING'",
                error, !preview, id, attempt);
    }

    /**
     * Asks runs well past their timeout to stop. The owner stops a run at its timeout by itself;
     * this is the backstop for an owner that does not, so that it shows on its next heartbeat.
     */
    public int cancelOverdue(Duration grace) {
        return jdbc.update("UPDATE flow.run SET cancel_requested = true WHERE status = 'RUNNING' "
                        + "AND NOT cancel_requested "
                        + "AND started_at + run_timeout + make_interval(secs => ?) < now()",
                (double) grace.toSeconds());
    }

    // ---------------------------------------------------------------- reading and cancelling

    public Optional<StoredRun> find(UUID id) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM flow.run r "
                + "LEFT JOIN flow.flow f ON f.id = r.flow_id WHERE r.id = ?", RUN, id).stream().findFirst();
    }

    /**
     * A page of runs of flows in {@code readable} datasets, newest first, optionally of one flow,
     * after the run created at {@code afterCreated} with id {@code afterId}.
     */
    public List<StoredRun> list(DataSets readable, Long flowId, Instant afterCreated, UUID afterId,
                                int limit) {
        if (!readable.all() && readable.ids().isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS + " FROM flow.run r "
                + "JOIN flow.flow f ON f.id = r.flow_id WHERE true");
        List<Object> args = new ArrayList<>();
        if (!readable.all()) {
            sql.append(" AND f.data_set_id = ANY (?)");
            args.add(readable.ids().toArray(Long[]::new));
        }
        if (flowId != null) {
            sql.append(" AND r.flow_id = ?");
            args.add(flowId);
        }
        if (afterCreated != null) {
            sql.append(" AND (r.created_at, r.id) < (?, ?)");
            args.add(timestamp(afterCreated));
            args.add(afterId);
        }
        sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), RUN, args.toArray());
    }

    /** A pending run is cancelled at once; true when it was. */
    public boolean cancelIfPending(UUID id, String by) {
        return jdbc.update("UPDATE flow.run SET status = 'CANCELLED', error_code = 'CANCELLED', "
                + "error = ?, finished_at = now() WHERE id = ? AND status = 'PENDING'",
                "Cancelled by " + by + ".", id) == 1;
    }

    /** A running run is asked to stop; its owner sees it at the next heartbeat. True when asked. */
    public boolean requestCancel(UUID id) {
        return jdbc.update("UPDATE flow.run SET cancel_requested = true WHERE id = ? "
                + "AND status = 'RUNNING'", id) == 1;
    }

    private static TimeWindow window(ResultSet rs) throws SQLException {
        Timestamp start = rs.getTimestamp("window_start");
        Timestamp end = rs.getTimestamp("window_end");
        return start == null ? null : new TimeWindow(start.toInstant(), end.toInstant());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
