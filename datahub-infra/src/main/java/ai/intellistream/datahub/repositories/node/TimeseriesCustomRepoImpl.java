// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.models.datafilters.FilterPatterns;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.jpa.domains.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.List;
import java.util.Map;

public class TimeseriesCustomRepoImpl implements TimeseriesCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;

    public TimeseriesCustomRepoImpl(
            EntityManager entityManager,
            EntityManagerFactory entityManagerFactory
    ) {
        this.entityManager = entityManager;
        this.entityManagerFactory = entityManagerFactory;
    }

    public static final String METADATA_REF = "metadata";
    private static final String VAL_TYPE_REF = "valueType";

    private static final String DP_UNIQUE_CONSTRAINT_NAME = "timeseries_datapoints_bigint_key";

    @Transactional(readOnly = true)
    public <T> List<T> list(int maxResults, Class<T> type) {
        /*var cb = entityManager.getCriteriaBuilder();
        var q = cb.createQuery(type);
        Root<TimeseriesEntity> root = q.from(TimeseriesEntity.class);
        setFetches(root);
        q.from(type);
        TypedQuery<T> query = entityManager.createQuery(q);
        query.setMaxResults(maxResults);
        return query.getResultList();*/

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> q = cb.createQuery(type);
        Root<TimeseriesEntity> root = q.from(TimeseriesEntity.class);
        setFetches(root);
        Order orderDesc = cb.desc(root.get("dateCreated"));
        q.orderBy(orderDesc);
        TypedQuery<T> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        var r = tq.getResultList();
        return r;
    }

    @Transactional(readOnly = true)
    public <T> List<T> list(int maxResults, NodeType nodeType, Class<T> type){
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> q = cb.createQuery(type);
        Root<TimeseriesEntity> root = q.from(TimeseriesEntity.class);
        q.where(cb.equal(root.get("nodeType"), nodeType));
        setFetches(root);
        Order orderDesc = cb.desc(root.get("dateCreated"));
        q.orderBy(orderDesc);
        TypedQuery<T> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        var r = tq.getResultList();
        return r;
    }

    private static void setFetches(Root<TimeseriesEntity> root) {
        root.fetch(METADATA_REF, JoinType.LEFT);
        root.fetch(VAL_TYPE_REF, JoinType.LEFT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeseriesEntity> list(int maxResults) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TimeseriesEntity> q = cb.createQuery(TimeseriesEntity.class);
        Root<TimeseriesEntity> root = q.from(TimeseriesEntity.class);
        setFetches(root);
        Order orderDesc = cb.desc(root.get("dateCreated"));
        // The `node` table is single-table inheritance shared by every node type. Unlike Spring Data
        // derived/JPQL queries (which DO emit the discriminator restriction), the Criteria API does
        // not add it for this entity, so without this explicit filter the query returns the most
        // recent N nodes of ANY type. Verified by TimeseriesNodeTypeFilterIT.
        q.select(root)
                .where(cb.equal(root.get("nodeType").get("id"), NodeType.TIMESERIES))
                .orderBy(orderDesc);
        TypedQuery<TimeseriesEntity> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        return tq.getResultList();
    }

    // ---- Dataset-ACL-narrowed variants --------------------------------------------------------

    /** OR a list of patterns against one column; nothing added when the list is empty. */
    private static void addAnyOf(CriteriaBuilder cb, List<Predicate> predicates,
                                 Root<TimeseriesEntity> root, String attribute, List<String> values) {
        List<String> patterns = FilterPatterns.allPatterns(values);
        if (patterns.isEmpty()) {
            return;
        }
        List<Predicate> matches = patterns.stream()
                .map(pattern -> (Predicate) cb.isTrue(cb.function(
                        "ILIKE_FN", Boolean.class, root.get(attribute), cb.literal(pattern))))
                .toList();
        predicates.add(cb.or(matches.toArray(new Predicate[0])));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeseriesEntity> search(String searchPhrase, int maxResults, Collection<Long> dataSetIds,
                                        TimeseriesFilter criteria) {
        return filter(maxResults, dataSetIds, criteria, NodeSort.DEFAULT, null, searchPhrase);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeseriesEntity> filter(int maxResults, Collection<Long> dataSetIds, TimeseriesFilter criteria) {
        return filter(maxResults, dataSetIds, criteria, NodeSort.DEFAULT, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeseriesEntity> filter(int maxResults, Collection<Long> dataSetIds, TimeseriesFilter criteria,
                                         NodeSort sort, PageCursor cursor) {
        return filter(maxResults, dataSetIds, criteria, sort, cursor, null);
    }

    private List<TimeseriesEntity> filter(int maxResults, Collection<Long> dataSetIds, TimeseriesFilter criteria,
                                          NodeSort sort, PageCursor cursor, String searchPhrase) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TimeseriesEntity> q = cb.createQuery(TimeseriesEntity.class);
        Root<TimeseriesEntity> root = q.from(TimeseriesEntity.class);
        setFetches(root);

        // The shared node criteria, plus the TIMESERIES discriminator. Timeseries gained
        // externalIdPrefix, source, labels, createdTime and lastUpdatedTime here for free: they were
        // absent only because this filter was written separately, not because the columns differ.
        List<Predicate> predicates = NodePredicateBuilder.build(cb, q, root, criteria, NodeType.TIMESERIES);

        if (searchPhrase != null && !searchPhrase.isBlank()) {
            predicates.add(NodePredicateBuilder.fullTextMatch(cb, root, searchPhrase));
        }

        if (dataSetIds != null) {
            if (dataSetIds.isEmpty()) {
                // Narrowed to no data sets at all. Return nothing rather than running a query with
                // an empty IN — dropping the predicate instead would widen this to every timeseries.
                return List.of();
            }
            predicates.add(NodePredicateBuilder.dataSetScope(root, dataSetIds));
        }

        // What is left is what only a timeseries has.
        if (criteria != null) {
            // Units follow the same pattern rules as the inherited name/source: * and % are
            // wildcards, an entry without one matches exactly, entries OR together.
            addAnyOf(cb, predicates, root, "unit", criteria.getUnit());
            addAnyOf(cb, predicates, root, "unitExternalId", criteria.getUnitExternalId());

            if (criteria.getValueType() != null && !criteria.getValueType().isEmpty()) {
                // A closed catalogue, so this is an exact IN over the joined value-type name rather
                // than a pattern match. Lower-cased to agree with how Timeseries.setValueType
                // normalises what it stores.
                List<String> wanted = criteria.getValueType().stream()
                        .filter(v -> v != null && !v.isBlank())
                        .map(v -> v.trim().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
                if (!wanted.isEmpty()) {
                    Join<TimeseriesEntity, TimeseriesValueType> vt = root.join(VAL_TYPE_REF, JoinType.INNER);
                    predicates.add(cb.lower(vt.get("name")).in(wanted));
                }
            }
        }

        if (cursor != null) {
            predicates.add(NodePredicateBuilder.keyset(cb, root, sort, cursor));
        }
        boolean ranked = searchPhrase != null && !searchPhrase.isBlank();
        q.select(root)
                // No DISTINCT: the metadata criterion is an EXISTS subquery rather than a join,
                // so nothing here multiplies rows any more. It also could not stay — Postgres
                // rejects an ORDER BY expression that is not in the select list of a SELECT
                // DISTINCT, which is every relevance-ordered search.
                .where(predicates.toArray(new Predicate[0]))
                .orderBy(ranked
                        ? NodePredicateBuilder.searchOrderBy(cb, root, searchPhrase)
                        : NodePredicateBuilder.orderBy(cb, root, sort));
        TypedQuery<TimeseriesEntity> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        return tq.getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeseriesEntity> list(int maxResults, Collection<Long> allowedDataSetIds) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TimeseriesEntity> q = cb.createQuery(TimeseriesEntity.class);
        Root<TimeseriesEntity> root = q.from(TimeseriesEntity.class);
        setFetches(root);
        Order orderDesc = cb.desc(root.get("dateCreated"));
        // Same Criteria-discriminator gap as list(int): filter node_type explicitly.
        q.select(root)
                .where(cb.and(
                        cb.equal(root.get("nodeType").get("id"), NodeType.TIMESERIES),
                        root.get("dataSet").get("id").in(allowedDataSetIds)))
                .orderBy(orderDesc);
        TypedQuery<TimeseriesEntity> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        return tq.getResultList();
    }

}
