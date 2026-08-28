// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services.graph;

import java.util.List;

/**
 * What one outbox row asks the graph mirror to do: re-mirror these nodes and edges, remove those.
 *
 * <p>It carries <em>ids, not values</em>. {@link ResourceGraphApplier} loads the current row from
 * Postgres when it applies the command, which is what makes the mirror self-correcting: a command
 * replayed after a crash, or applied late, writes the state Postgres holds now rather than
 * restoring the state that was current when it was queued. It also means a field added to a node
 * entity reaches the graph without touching this type — the constraint that the old Avro-over-
 * Pulsar payload imposed is gone.
 *
 * <p>Deletes are the exception and must carry their identifiers inline: by the time the command
 * is applied the row is gone, so there is nothing left to read.
 *
 * @param upsertNodeIds nodes to write from current Postgres state; ids that no longer exist are skipped
 * @param upsertEdgeIds edges to write from current Postgres state; ids that no longer exist are skipped
 * @param deleteNodes   nodes to remove, identified by id or (when the id was never known) externalId
 * @param deleteEdgeIds edges to remove
 */
public record GraphSyncCommand(List<Long> upsertNodeIds,
                               List<Long> upsertEdgeIds,
                               List<NodeRef> deleteNodes,
                               List<Long> deleteEdgeIds) {

    /** A node to delete. {@code id} is preferred; {@code externalId} is the fallback. */
    public record NodeRef(Long id, String externalId) {
    }

    public GraphSyncCommand {
        upsertNodeIds = upsertNodeIds == null ? List.of() : List.copyOf(upsertNodeIds);
        upsertEdgeIds = upsertEdgeIds == null ? List.of() : List.copyOf(upsertEdgeIds);
        deleteNodes = deleteNodes == null ? List.of() : List.copyOf(deleteNodes);
        deleteEdgeIds = deleteEdgeIds == null ? List.of() : List.copyOf(deleteEdgeIds);
    }

    public boolean isEmpty() {
        return upsertNodeIds.isEmpty() && upsertEdgeIds.isEmpty()
                && deleteNodes.isEmpty() && deleteEdgeIds.isEmpty();
    }
}
