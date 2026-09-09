// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.asset;

import java.util.Set;

/**
 * Which datasets a graph read is allowed to return.
 *
 * <p>The graph traversals are the one read path that cannot narrow in SQL: Neo4j returns whatever
 * is reachable, and reachability does not stop at a dataset boundary. Gating only the node the
 * traversal starts from — which is what {@code /resources/export}, {@code /fetch-related} and
 * {@code /fetch-nearest} used to do — means one read grant plus one edge is enough to pull back
 * nodes from a dataset the caller cannot read. This carries the caller's answer down to
 * {@link ResourceNetwork}, which is the last point before the graph becomes a response.
 *
 * <p>Deliberately a value rather than a reference to the security service: this lives in
 * datahub-infra, and {@code DataSecurity} lives in datahub-api. The caller resolves permissions and
 * passes the result down.
 */
public record GraphReadScope(boolean everything, Set<Long> allowedDataSetIds) {

    public GraphReadScope {
        allowedDataSetIds = allowedDataSetIds == null ? Set.of() : Set.copyOf(allowedDataSetIds);
    }

    /**
     * No narrowing. For an all-datasets reader, and for internal reads that must see the whole
     * graph to be correct — {@code fetchComponentForNodes} answers "would this delete disjoint the
     * graph?", and a half-visible component answers it wrongly.
     */
    public static GraphReadScope readEverything() {
        return new GraphReadScope(true, Set.of());
    }

    /** Only these dataset ids, which is the caller's grants already expanded down the hierarchy. */
    public static GraphReadScope restrictedTo(Set<Long> allowedDataSetIds) {
        return new GraphReadScope(false, allowedDataSetIds);
    }

    /**
     * Whether a node in this dataset may be returned. A {@code null} id is an orphan node, which
     * only an all-datasets reader may see — the same rule {@code DataSecurity} applies to every
     * other read, and the reason this is not simply a set membership test.
     */
    public boolean permits(Long dataSetId) {
        if (everything) {
            return true;
        }
        return dataSetId != null && allowedDataSetIds.contains(dataSetId);
    }
}
