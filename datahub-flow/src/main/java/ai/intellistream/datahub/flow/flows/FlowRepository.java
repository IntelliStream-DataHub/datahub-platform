// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.access.Caller.DataSets;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The flows of the current tenant and their versions, in its {@code flow} schema (V46). Every name is
 * schema-qualified: connections go through pgbouncer in transaction mode, so there is no
 * {@code search_path} to rely on.
 *
 * <p>A version is written once and never changed (the table refuses updates); a flow's current
 * version moves forward with every save. Callers run the writes in one transaction.
 */
@Repository
public class FlowRepository {

    private static final String FLOW_COLUMNS = "id, external_id, data_set_id, data_set_external_id, "
            + "name, current_version, deployed_version, enabled, created_by, created_at, updated_by, "
            + "updated_at";

    private static final RowMapper<StoredFlow> FLOW = (rs, n) -> new StoredFlow(
            rs.getLong("id"), rs.getString("external_id"), rs.getLong("data_set_id"),
            rs.getString("data_set_external_id"), rs.getString("name"), rs.getInt("current_version"),
            (Integer) rs.getObject("deployed_version"), rs.getBoolean("enabled"),
            rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
            rs.getString("updated_by"), instant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public FlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A flow as stored, without its definition. */
    public record StoredFlow(long id, String externalId, long dataSetId, String dataSetExternalId,
                             String name, int currentVersion, Integer deployedVersion, boolean enabled,
                             String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
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
                             String definition, String by) {
        // The flow's current version is checked against flow_version by a deferred key, so the
        // flow goes first and its version second, in the caller's transaction.
        Long id = jdbc.queryForObject("INSERT INTO flow.flow (external_id, data_set_id, "
                        + "data_set_external_id, name, current_version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, 1, ?, ?) RETURNING id",
                Long.class, externalId, dataSetId, dataSetExternalId, name, by, by);
        insertVersion(id, 1, definition, by);
        return find(externalId).orElseThrow();
    }

    /**
     * Saves {@code definition} as the flow's next version, provided the current version is still
     * {@code expectedVersion}; empty when someone else saved first.
     */
    public Optional<StoredFlow> addVersion(long flowId, int expectedVersion, long dataSetId,
                                           String dataSetExternalId, String name, String definition,
                                           String by) {
        int updated = jdbc.update("UPDATE flow.flow SET current_version = current_version + 1, "
                        + "data_set_id = ?, data_set_external_id = ?, name = ?, updated_by = ?, "
                        + "updated_at = now() WHERE id = ? AND current_version = ?",
                dataSetId, dataSetExternalId, name, by, flowId, expectedVersion);
        if (updated == 0) {
            return Optional.empty();
        }
        insertVersion(flowId, expectedVersion + 1, definition, by);
        return findById(flowId);
    }

    public Optional<StoredFlow> find(String externalId) {
        return jdbc.query("SELECT " + FLOW_COLUMNS + " FROM flow.flow WHERE external_id = ?",
                FLOW, externalId).stream().findFirst();
    }

    private Optional<StoredFlow> findById(long id) {
        return jdbc.query("SELECT " + FLOW_COLUMNS + " FROM flow.flow WHERE id = ?", FLOW, id)
                .stream().findFirst();
    }

    /**
     * A page of the flows in {@code readable} datasets, by external id, starting after
     * {@code afterExternalId} (null for the first page).
     */
    public List<StoredFlow> list(DataSets readable, String afterExternalId, int limit) {
        if (!readable.all() && readable.ids().isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + FLOW_COLUMNS + " FROM flow.flow WHERE true");
        List<Object> args = new ArrayList<>();
        if (!readable.all()) {
            sql.append(" AND data_set_id = ANY (?)");
            // The driver binds a Long[] as a bigint[].
            args.add(readable.ids().toArray(Long[]::new));
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
                        + "FROM flow.flow_version WHERE flow_id = ? AND version = ?",
                (rs, n) -> new StoredVersion(rs.getInt("version"), rs.getString("definition"),
                        rs.getString("created_by"), instant(rs.getTimestamp("created_at"))),
                flowId, version).stream().findFirst();
    }

    /** Every version of a flow, newest first, without their definitions. */
    public List<StoredVersion> versions(long flowId) {
        return jdbc.query("SELECT version, created_by, created_at FROM flow.flow_version "
                        + "WHERE flow_id = ? ORDER BY version DESC",
                (rs, n) -> new StoredVersion(rs.getInt("version"), null, rs.getString("created_by"),
                        instant(rs.getTimestamp("created_at"))),
                flowId);
    }

    private void insertVersion(long flowId, int version, String definition, String by) {
        jdbc.update("INSERT INTO flow.flow_version (flow_id, version, definition, created_by) "
                + "VALUES (?, ?, ?::jsonb, ?)", flowId, version, definition, by);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
