// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.access.Caller.DataSets;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The flows of the current tenant and their versions, in its {@code flow} and {@code flow_version}
 * tables (V46). Names are unqualified: the tables are in the default schema, which is all a
 * pgbouncer connection in transaction mode has.
 *
 * <p>A version is written once and never changed (the table refuses updates); a flow's current
 * version moves forward with every save. Callers run the writes in one transaction.
 */
@Repository
public class FlowRepository {

    /**
     * Why the service switched a flow off when its dataset was deleted. Such a flow is
     * <em>orphaned</em>: nobody sees it through dataset grants any more, so it is shown to whoever
     * may write every dataset, to move it to another dataset or delete it.
     */
    public static final String DATASET_DELETED = "DATASET_DELETED";

    private static final String FLOW_COLUMNS = "id, external_id, data_set_id, data_set_external_id, "
            + "name, current_version, deployed_version, enabled, created_by, created_at, updated_by, "
            + "updated_at, disabled_reason, kind";

    private static final RowMapper<StoredFlow> FLOW = (rs, n) -> new StoredFlow(
            rs.getLong("id"), rs.getString("external_id"), rs.getLong("data_set_id"),
            rs.getString("data_set_external_id"), rs.getString("name"), rs.getInt("current_version"),
            (Integer) rs.getObject("deployed_version"), rs.getBoolean("enabled"),
            rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
            rs.getString("updated_by"), instant(rs.getTimestamp("updated_at")),
            rs.getString("disabled_reason"), rs.getString("kind"));

    private final JdbcTemplate jdbc;

    public FlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A flow as stored, without its definition.
     *
     * @param disabledReason why the service switched it off, or null
     * @param kind           {@code flow} or {@code agent}: a flow whose step is the agent processor
     */
    public record StoredFlow(long id, String externalId, long dataSetId, String dataSetExternalId,
                             String name, int currentVersion, Integer deployedVersion, boolean enabled,
                             String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                             String disabledReason, String kind) {

        /** Its dataset was deleted: see {@link #DATASET_DELETED}. */
        public boolean orphaned() {
            return DATASET_DELETED.equals(disabledReason);
        }
    }

    /** A saved version. */
    public record StoredVersion(int version, String definition, String createdBy, Instant createdAt) {
    }

    /**
     * Inserts a flow and its version 1.
     *
     * @throws DuplicateKeyException when the tenant already has a flow with that external id
     */
    public StoredFlow create(String externalId, long dataSetId, String dataSetExternalId, String name,
                             String kind, String definition, String by) {
        // The flow's current version is checked against flow_version by a deferred key, so the
        // flow goes first and its version second, in the caller's transaction.
        Long id = jdbc.queryForObject("INSERT INTO flow (external_id, data_set_id, "
                        + "data_set_external_id, name, kind, current_version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, ?) RETURNING id",
                Long.class, externalId, dataSetId, dataSetExternalId, name, kind, by, by);
        insertVersion(id, 1, definition, by);
        return find(externalId).orElseThrow();
    }

    /**
     * Saves {@code definition} as the flow's next version, provided the current version is still
     * {@code expectedVersion}; empty when someone else saved first. An orphaned flow saved into
     * another dataset is no longer orphaned; it stays switched off until someone deploys it.
     */
    public Optional<StoredFlow> addVersion(long flowId, int expectedVersion, long dataSetId,
                                           String dataSetExternalId, String name, String kind,
                                           String definition, String by) {
        // Every expression in SET reads the row as it was, so data_set_id here is the old dataset.
        int updated = jdbc.update("UPDATE flow SET current_version = current_version + 1, "
                        + "disabled_reason = CASE WHEN disabled_reason = ? AND data_set_id <> ? "
                        + "THEN NULL ELSE disabled_reason END, "
                        + "data_set_id = ?, data_set_external_id = ?, name = ?, kind = ?, updated_by = ?, "
                        + "updated_at = now() WHERE id = ? AND current_version = ?",
                DATASET_DELETED, dataSetId, dataSetId, dataSetExternalId, name, kind, by, flowId,
                expectedVersion);
        if (updated == 0) {
            return Optional.empty();
        }
        insertVersion(flowId, expectedVersion + 1, definition, by);
        return findById(flowId);
    }

    public Optional<StoredFlow> find(String externalId) {
        return jdbc.query("SELECT " + FLOW_COLUMNS + " FROM flow WHERE external_id = ?",
                FLOW, externalId).stream().findFirst();
    }

    public Optional<StoredFlow> findById(long id) {
        return jdbc.query("SELECT " + FLOW_COLUMNS + " FROM flow WHERE id = ?", FLOW, id)
                .stream().findFirst();
    }

    /**
     * A page of the flows in {@code readable} datasets — and the orphaned ones too, when
     * {@code orphans} — of one {@code kind} (null for every kind), by external id, starting after
     * {@code afterExternalId} (null for the first page).
     */
    public List<StoredFlow> list(DataSets readable, boolean orphans, String kind, String afterExternalId,
                                 int limit) {
        if (!readable.all() && readable.ids().isEmpty() && !orphans) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + FLOW_COLUMNS + " FROM flow WHERE true");
        List<Object> args = new ArrayList<>();
        if (kind != null) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        if (!readable.all()) {
            // The driver binds a Long[] as a bigint[].
            sql.append(orphans ? " AND (data_set_id = ANY (?) OR disabled_reason = ?)"
                    : " AND data_set_id = ANY (?)");
            args.add(readable.ids().toArray(Long[]::new));
            if (orphans) {
                args.add(DATASET_DELETED);
            }
        }
        if (afterExternalId != null) {
            sql.append(" AND external_id > ?");
            args.add(afterExternalId);
        }
        sql.append(" ORDER BY external_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), FLOW, args.toArray());
    }

    /** The definition of a flow's {@code version}; empty when there is no such version. */
    public Optional<StoredVersion> version(long flowId, int version) {
        return jdbc.query("SELECT version, definition::text AS definition, created_by, created_at "
                        + "FROM flow_version WHERE flow_id = ? AND version = ?",
                (rs, n) -> new StoredVersion(rs.getInt("version"), rs.getString("definition"),
                        rs.getString("created_by"), instant(rs.getTimestamp("created_at"))),
                flowId, version).stream().findFirst();
    }

    /** Every version of a flow, newest first, without their definitions. */
    public List<StoredVersion> versions(long flowId) {
        return jdbc.query("SELECT version, created_by, created_at FROM flow_version "
                        + "WHERE flow_id = ? ORDER BY version DESC",
                (rs, n) -> new StoredVersion(rs.getInt("version"), null, rs.getString("created_by"),
                        instant(rs.getTimestamp("created_at"))),
                flowId);
    }

    /**
     * A flow's deployment: the version switched on, its trigger and limits, whose access its runs
     * use, and where its schedule stands.
     *
     * @param trigger the trigger as deployed ({@code Schedule#toJson})
     */
    public record Deployment(int version, boolean enabled, String disabledReason, String trigger,
                             int maxConcurrentRuns, int maxAttempts, Duration timeout,
                             String identityKind, String identitySubject, String identityClientName,
                             String identityState, String identityStateReason, String deployedBy,
                             Instant deployedAt, Instant nextTick, Instant nextDueAt, Instant lastTick) {
    }

    /** The flow's deployment; empty when it has never been deployed. */
    public Optional<Deployment> deployment(long flowId) {
        return jdbc.query("SELECT deployed_version, enabled, disabled_reason, trigger::text AS trigger, "
                        + "max_concurrent_runs, max_attempts, "
                        + "extract(epoch FROM run_timeout)::bigint AS timeout_seconds, "
                        + "identity_kind, identity_subject, identity_client_name, identity_state, "
                        + "identity_state_reason, deployed_by, deployed_at, next_tick, next_due_at, "
                        + "last_tick FROM flow WHERE id = ? AND deployed_version IS NOT NULL",
                (rs, n) -> new Deployment(rs.getInt("deployed_version"), rs.getBoolean("enabled"),
                        rs.getString("disabled_reason"), rs.getString("trigger"),
                        rs.getInt("max_concurrent_runs"), rs.getInt("max_attempts"),
                        Duration.ofSeconds(rs.getLong("timeout_seconds")),
                        rs.getString("identity_kind"), rs.getString("identity_subject"),
                        rs.getString("identity_client_name"), rs.getString("identity_state"),
                        rs.getString("identity_state_reason"), rs.getString("deployed_by"),
                        instant(rs.getTimestamp("deployed_at")), instant(rs.getTimestamp("next_tick")),
                        instant(rs.getTimestamp("next_due_at")), instant(rs.getTimestamp("last_tick"))),
                flowId).stream().findFirst();
    }

    /**
     * Whose access a flow's runs use: the person who deployed it ({@link #DEPLOYER}, by their
     * {@code sub}, with an offline token kept in Vault) or a registered client ({@link #CLIENT}, by
     * its name in the tenant's flow settings).
     */
    public record Identity(String kind, String subject, String clientName) {

        public static final String DEPLOYER = "deployer";
        public static final String CLIENT = "client";

        public static Identity deployer(String subject) {
            return new Identity(DEPLOYER, subject, null);
        }

        public static Identity client(String name) {
            return new Identity(CLIENT, null, name);
        }

        /** What a run records as having acted: the subject or the client's name. */
        public String runAs() {
            return DEPLOYER.equals(kind) ? subject : clientName;
        }
    }

    /**
     * Deploys {@code version}: its trigger and limits replace the previous deployment's. With an
     * {@code identity}, the runs act as it from now on and it starts out usable; without one, the
     * deployment keeps the identity it has — changing the version or switching a flow on or off is
     * not a new authorisation. The schedule starts afresh at {@code nextTick} (null for a manual
     * trigger or a disabled deployment): ticks missed while a flow was not deployed are not caught
     * up, only those missed while the service was down.
     */
    public void deploy(long flowId, int version, boolean enabled, String trigger, int maxConcurrentRuns,
                       int maxAttempts, Duration timeout, Identity identity,
                       String deployedBy, Instant nextTick, Instant nextDueAt) {
        String identityColumns = identity == null ? ""
                : "identity_kind = ?, identity_subject = ?, identity_client_name = ?, "
                + "identity_state = 'ok', identity_state_reason = NULL, ";
        List<Object> args = new ArrayList<>(List.of(version, enabled, trigger, maxConcurrentRuns,
                maxAttempts, (double) timeout.toSeconds()));
        if (identity != null) {
            args.add(identity.kind());
            args.add(identity.subject());
            args.add(identity.clientName());
        }
        args.add(deployedBy);
        args.add(timestamp(nextTick));
        args.add(timestamp(nextDueAt));
        args.add(flowId);
        jdbc.update("UPDATE flow SET deployed_version = ?, enabled = ?, disabled_reason = NULL, "
                        + "trigger = ?::jsonb, max_concurrent_runs = ?, max_attempts = ?, "
                        + "run_timeout = make_interval(secs => ?), "
                        + identityColumns
                        + "deployed_by = ?, deployed_at = now(), next_tick = ?, next_due_at = ?, "
                        + "last_tick = NULL WHERE id = ?",
                args.toArray());
    }

    /**
     * The identity a deployed flow's runs act as, and whether it is usable; empty for a flow never
     * deployed.
     */
    public Optional<IdentityState> identity(long flowId) {
        return jdbc.query("SELECT identity_kind, identity_subject, identity_client_name, identity_state, "
                        + "identity_state_reason FROM flow WHERE id = ? AND identity_kind IS NOT NULL",
                (rs, n) -> new IdentityState(new Identity(rs.getString("identity_kind"),
                        rs.getString("identity_subject"), rs.getString("identity_client_name")),
                        "ok".equals(rs.getString("identity_state")),
                        rs.getString("identity_state_reason")),
                flowId).stream().findFirst();
    }

    /** A deployment's identity and whether Keycloak still accepts it. */
    public record IdentityState(Identity identity, boolean usable, String reason) {
    }

    /**
     * Keycloak refused the identity a flow's runs act as (a revoked offline token, a client whose
     * secret changed): no more runs are created for it until someone deploys it afresh.
     */
    public void needsReauthorisation(long flowId, String reason) {
        jdbc.update("UPDATE flow SET identity_state = 'needs-reauthorisation', "
                + "identity_state_reason = ? WHERE id = ? AND identity_state = 'ok'", reason, flowId);
    }

    // ---------------------------------------------------------------- what the run store is told

    /**
     * A deployed flow as dispatch and the ticker need it: its limits, and its schedule as it was
     * deployed. {@code identityOk} is false for a deployment whose identity Keycloak refused, which gets
     * no scheduled runs until it is deployed afresh: each would only fail the same way.
     *
     * @param trigger    the deployed trigger as JSON
     * @param nextTick   the schedule's next tick as last checkpointed here, null when it has none
     * @param deployedAt when it was deployed: a different one means the schedule starts over
     */
    public record DeployedFlow(long id, String externalId, long dataSetId, boolean enabled, int maxConcurrentRuns,
                               int version, String trigger, Instant nextTick, Instant deployedAt, int maxAttempts,
                               Duration timeout, String runAs, boolean identityOk) {
    }

    private static final String DEPLOYED = "SELECT id, external_id, data_set_id, enabled, max_concurrent_runs, "
            + "deployed_version, trigger::text AS trigger, next_tick, deployed_at, max_attempts, "
            + "extract(epoch FROM run_timeout)::bigint AS timeout_seconds, "
            + "coalesce(identity_subject, identity_client_name) AS run_as, identity_state FROM flow "
            + "WHERE deployed_version IS NOT NULL";

    private static DeployedFlow deployed(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DeployedFlow(rs.getLong("id"), rs.getString("external_id"), rs.getLong("data_set_id"),
                rs.getBoolean("enabled"), rs.getInt("max_concurrent_runs"), rs.getInt("deployed_version"),
                rs.getString("trigger"), instant(rs.getTimestamp("next_tick")),
                instant(rs.getTimestamp("deployed_at")), rs.getInt("max_attempts"),
                Duration.ofSeconds(rs.getLong("timeout_seconds")), rs.getString("run_as"),
                "ok".equals(rs.getString("identity_state")));
    }

    /** One deployed flow, for telling the run store of it at once. */
    public Optional<DeployedFlow> deployedFlow(long flowId) {
        return jdbc.query(DEPLOYED + " AND id = ?", (rs, n) -> deployed(rs), flowId).stream().findFirst();
    }

    /** Every deployed flow, for telling the run store of all of them. */
    public List<DeployedFlow> deployedFlows() {
        return jdbc.query(DEPLOYED, (rs, n) -> deployed(rs));
    }

    /**
     * Where a flow's schedule had got to, as the run store knows it.
     *
     * @param nextTick the tick it waits for; null when the cron expression has no more
     * @param lastTick the latest tick made into a run, null if none yet
     */
    public record ScheduleCheckpoint(long flowId, Instant deployedAt, Instant nextTick, Instant nextDueAt,
                                     Instant lastTick) {
    }

    /**
     * Saves where schedules had got to, so that a run store that lost them can start again from here
     * instead of from the deployment. A checkpoint never moves a schedule back, and never lands on a
     * deployment other than the one it was taken from; the run store is the authority, this is its
     * backup, and the Runs overview reads it.
     */
    public void checkpointSchedules(List<ScheduleCheckpoint> checkpoints) {
        List<Object[]> moving = new java.util.ArrayList<>();
        List<Object[]> finished = new java.util.ArrayList<>();
        for (ScheduleCheckpoint c : checkpoints) {
            if (c.nextTick() == null) {
                finished.add(new Object[]{timestamp(c.lastTick()), c.flowId(), timestamp(c.deployedAt())});
            } else {
                moving.add(new Object[]{timestamp(c.nextTick()), timestamp(c.nextDueAt()), timestamp(c.lastTick()),
                        c.flowId(), timestamp(c.deployedAt()), timestamp(c.nextTick())});
            }
        }
        if (!moving.isEmpty()) {
            jdbc.batchUpdate("UPDATE flow SET next_tick = ?, next_due_at = ?, last_tick = coalesce(?, last_tick) "
                    + "WHERE id = ? AND deployed_at = ? AND (next_tick IS NULL OR next_tick < ?)", moving);
        }
        if (!finished.isEmpty()) {
            jdbc.batchUpdate("UPDATE flow SET next_tick = NULL, next_due_at = NULL, "
                    + "last_tick = coalesce(?, last_tick) WHERE id = ? AND deployed_at = ?", finished);
        }
    }

    /** The database's clock. */
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    // ---------------------------------------------------------------- the overview

    /** A deployed flow as the Runs overview starts from it; its runs come from the run store. */
    public record BoardFlow(long id, String externalId, String name, int deployedVersion, boolean enabled,
                            String disabledReason, String identityState, Instant nextDueAt) {
    }

    /** The deployed flows in {@code readable} datasets, by external id after {@code afterExternalId}. */
    public List<BoardFlow> deployedBoard(DataSets readable, String afterExternalId, int limit) {
        if (!readable.all() && readable.ids().isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT id, external_id, name, deployed_version, enabled, "
                + "disabled_reason, identity_state, next_due_at FROM flow WHERE deployed_version IS NOT NULL");
        List<Object> args = new ArrayList<>();
        if (!readable.all()) {
            sql.append(" AND data_set_id = ANY (?)");
            args.add(readable.ids().toArray(Long[]::new));
        }
        if (afterExternalId != null) {
            sql.append(" AND external_id > ?");
            args.add(afterExternalId);
        }
        sql.append(" ORDER BY external_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> new BoardFlow(rs.getLong("id"), rs.getString("external_id"),
                rs.getString("name"), rs.getInt("deployed_version"), rs.getBoolean("enabled"),
                rs.getString("disabled_reason"), rs.getString("identity_state"),
                instant(rs.getTimestamp("next_due_at"))), args.toArray());
    }

    /** Deletes a flow with its versions and runs. */
    public void delete(long flowId) {
        jdbc.update("DELETE FROM flow WHERE id = ?", flowId);
    }

    /** Switches a flow off on the service's own account, saying why, and stops its schedule. */
    public void disable(long flowId, String reason) {
        jdbc.update("UPDATE flow SET enabled = false, disabled_reason = ?, next_tick = NULL, "
                + "next_due_at = NULL WHERE id = ?", reason, flowId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private void insertVersion(long flowId, int version, String definition, String by) {
        jdbc.update("INSERT INTO flow_version (flow_id, version, definition, created_by) "
                + "VALUES (?, ?, ?::jsonb, ?)", flowId, version, definition, by);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
