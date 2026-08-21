// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.policy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every naming policy configured for the current tenant, in one query.
 *
 * <p>Deliberately one query rather than a lookup per data set: the result is cached wholesale and
 * naming policies are few (one tenant default plus the handful of data sets that override it), so
 * fetching all of them costs less than the round trips a per-data-set lookup would add to the write
 * path.
 *
 * <p>A policy is a node ({@code node_type = 6}) whose metadata carries {@code kind = naming}. It is
 * <em>attached</em> to a data set by an {@code ENFORCED_ON} edge running from the data set to the
 * policy — the same edge {@code PolicyService.createEmptyPolicy} creates. A policy with no such edge
 * is the tenant default.
 */
@Slf4j
@Repository
public class NamingPolicyRepository {

    private static final long POLICY_NODE_TYPE = 6L;

    /**
     * Naming policies with the data set each is attached to, or null where it is the tenant default.
     *
     * <p>{@code LEFT JOIN} on the edge so an unattached policy still comes back; a plain join would
     * silently drop exactly the tenant-wide rule that governs everything.
     *
     * <p>Deactivated policies are excluded here rather than filtered afterwards, so a switched-off
     * rule costs nothing to carry and whatever it overrode applies again: a deactivated data set
     * policy falls back to the tenant's, a deactivated tenant policy to the shipped default.
     */
    private static final String SELECT_POLICIES = """
            SELECT p.id, p.external_id, p.name, e.rel_start AS data_set_id
            FROM node p
            LEFT JOIN edge e
                   ON e.rel_end = p.id
                  AND e.relationship_type_id = (SELECT rt.id FROM relationship_type rt
                                                 WHERE rt.name = 'ENFORCED_ON' LIMIT 1)
            WHERE p.node_type = :policyType
              AND NOT p.is_deactivated
              AND EXISTS (SELECT 1 FROM node_metadata m
                           WHERE m.node_id = p.id AND m.key = 'kind' AND m.value = 'naming')
            """;

    private static final String SELECT_METADATA = """
            SELECT m.node_id, m.key, m.value
            FROM node_metadata m
            WHERE m.node_id IN (:policyIds)
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @return one row per (policy, attachment). A policy attached to several data sets appears
     *         several times, which is why the caller keys by data set rather than by policy
     */
    @Transactional(readOnly = true)
    public List<NamingPolicyRow> findAll() {
        List<?> rows = entityManager.createNativeQuery(SELECT_POLICIES)
                .setParameter("policyType", POLICY_NODE_TYPE)
                .getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> policyIds = new ArrayList<>();
        List<NamingPolicyRow> result = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            Long policyId = toLong(cells[0]);
            policyIds.add(policyId);
            result.add(new NamingPolicyRow(policyId, (String) cells[1], (String) cells[2],
                    toLong(cells[3]), new HashMap<>()));
        }

        // Second query rather than a join, so a policy with ten metadata keys does not multiply its
        // attachment rows tenfold and force de-duplication in Java.
        Map<Long, Map<String, String>> metadataByPolicy = new HashMap<>();
        List<?> metaRows = entityManager.createNativeQuery(SELECT_METADATA)
                .setParameter("policyIds", policyIds)
                .getResultList();
        for (Object row : metaRows) {
            Object[] cells = (Object[]) row;
            metadataByPolicy
                    .computeIfAbsent(toLong(cells[0]), k -> new HashMap<>())
                    .put((String) cells[1], (String) cells[2]);
        }

        List<NamingPolicyRow> withMetadata = new ArrayList<>(result.size());
        for (NamingPolicyRow row : result) {
            withMetadata.add(new NamingPolicyRow(row.policyId(), row.externalId(), row.name(),
                    row.dataSetId(), metadataByPolicy.getOrDefault(row.policyId(), Map.of())));
        }
        return withMetadata;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /**
     * @param dataSetId the data set this policy is attached to, or null when it is the tenant default
     */
    public record NamingPolicyRow(
            Long policyId,
            String externalId,
            String name,
            Long dataSetId,
            Map<String, String> metadata) {
    }
}
