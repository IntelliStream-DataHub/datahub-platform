// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.policy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The near-duplicate guard's one query: which stored external ids fold to the same value as the ones
 * about to be written.
 *
 * <p>Uniqueness already catches ids that differ only in case, because {@code external_id_hash} is
 * computed from the lowercased value. This catches the fuzzier case hashing cannot: ids that differ
 * only in which separator was typed. {@code pump-a-01} landing beside an existing {@code pump_a_01}
 * is the anomaly the original snake_case rule was introduced to prevent — deployments accumulated
 * mixed separators for one naming intent, and searches silently missed records because callers could
 * not predict which separator a given id used. With ids now stored verbatim, this guard is what
 * replaces that protection.
 *
 * <p><strong>Tenant-scoped, matching the uniqueness index it approximates.</strong> A per-data-set
 * scope would let {@code pump-a-01} sit in one data set beside {@code pump_a_01} in another, when
 * those two cannot both be <em>the</em> identifier for one physical asset. The harm is tenant-wide
 * too: the anomaly is searches that miss records, and search spans data sets.
 */
@Repository
public class NearDuplicateRepository {

    /**
     * The folding expression, written to match {@code node_external_id_folded_idx} (migration V32)
     * character for character.
     *
     * <p>PostgreSQL matches an expression index by comparing the parsed expression, so a difference
     * as small as a reordered {@code translate} set means the planner ignores the index and every
     * batch write becomes a sequential scan over the whole node table. It is also the SQL twin of
     * {@code ExternalIds.fold}; the two must agree or the guard finds candidates the application
     * then disagrees about.
     */
    private static final String FOLD = "lower(translate(n.external_id, '-.:+=', '_____'))";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Find stored external ids whose folded form is one of {@code foldedValues}.
     *
     * <p>One query per batch, never per item — the guard runs on the write path and a thousand-item
     * import must not become a thousand round trips.
     *
     * @param foldedValues folded candidate values, as produced by {@code ExternalIds.fold}
     * @param excludeNodeIds ids to ignore, so an update does not report the entity as its own
     *                       near-duplicate
     * @return folded value → an existing external id that folds to it. One example per folded value
     *         is enough: the message names a conflict, it does not enumerate them
     */
    @Transactional(readOnly = true)
    public Map<String, String> findExistingByFoldedValue(Collection<String> foldedValues,
                                                         Collection<Long> excludeNodeIds) {
        if (foldedValues == null || foldedValues.isEmpty()) {
            return Map.of();
        }

        boolean excluding = excludeNodeIds != null && !excludeNodeIds.isEmpty();
        String sql = "SELECT " + FOLD + " AS folded, n.external_id"
                + " FROM node n WHERE " + FOLD + " IN (:folded)"
                + (excluding ? " AND n.id NOT IN (:excluded)" : "");

        Query query = entityManager.createNativeQuery(sql).setParameter("folded", foldedValues);
        if (excluding) {
            query.setParameter("excluded", excludeNodeIds);
        }

        List<?> rows = query.getResultList();
        Map<String, String> byFolded = new LinkedHashMap<>();
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            // putIfAbsent: several stored ids can fold to the same value, and naming one of them is
            // the point. Keeping the first keeps the message stable across repeated calls.
            byFolded.putIfAbsent((String) cells[0], (String) cells[1]);
        }
        return byFolded;
    }

    /** Convenience for the single-value case (preflight). */
    @Transactional(readOnly = true)
    public Map<String, String> findExistingByFoldedValue(Collection<String> foldedValues) {
        return findExistingByFoldedValue(foldedValues, new ArrayList<>());
    }
}
