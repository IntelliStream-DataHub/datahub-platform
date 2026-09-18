// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.models.datafilters.FunctionFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public class FunctionCustomRepoImpl implements FunctionCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<FunctionEntity> filter(int maxResults, Collection<Long> dataSetIds, FunctionFilter criteria,
                                       NodeSort sort, PageCursor cursor) {
        return query(null, maxResults, dataSetIds, criteria, sort, cursor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FunctionEntity> search(String searchPhrase, int maxResults, Collection<Long> dataSetIds,
                                       FunctionFilter criteria) {
        return query(searchPhrase, maxResults, dataSetIds, criteria, NodeSort.DEFAULT, null);
    }

    /**
     * One query for both, because search is the filter plus a phrase. No fetch joins: the metadata
     * map is an element collection, and fetching a collection alongside {@code setMaxResults} makes
     * Hibernate page in memory — it would read every matching function to hand back one page.
     * Callers run inside a read-only transaction, so the transformer initialises what it copies.
     */
    private List<FunctionEntity> query(String searchPhrase, int maxResults, Collection<Long> dataSetIds,
                                       FunctionFilter criteria, NodeSort sort, PageCursor cursor) {
        FunctionFilter filter = criteria != null ? criteria : new FunctionFilter();

        if (dataSetIds != null && dataSetIds.isEmpty()) {
            // Narrowed to no data sets at all. Return nothing rather than running a query with an
            // empty IN — dropping the predicate instead would widen this to every function.
            return List.of();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<FunctionEntity> q = cb.createQuery(FunctionEntity.class);
        Root<FunctionEntity> root = q.from(FunctionEntity.class);

        // Every criterion a function can be filtered by is a shared node criterion, so this method
        // is the builder plus a discriminator. The discriminator has to be passed explicitly: the
        // Criteria API does not add it for these entities the way derived and JPQL queries do, and
        // a typed query that omits it returns rows of every node type.
        List<Predicate> predicates = NodePredicateBuilder.build(cb, q, root, filter, NodeType.FUNCTION);

        boolean ranked = searchPhrase != null && !searchPhrase.isBlank();
        if (ranked) {
            predicates.add(NodePredicateBuilder.fullTextMatch(cb, root, searchPhrase));
        }

        if (dataSetIds != null) {
            predicates.add(NodePredicateBuilder.dataSetScope(root, dataSetIds));
        }

        if (cursor != null) {
            predicates.add(NodePredicateBuilder.keyset(cb, root, sort, cursor));
        }

        q.select(root)
                // No DISTINCT: the metadata criterion is an EXISTS subquery rather than a join, so
                // nothing here multiplies rows. It also could not stay — Postgres rejects an ORDER
                // BY expression that is not in the select list of a SELECT DISTINCT, which is every
                // relevance-ordered search.
                .where(predicates.toArray(new Predicate[0]))
                // A search orders by relevance; a filter by the caller's sort. A filter has no
                // phrase to rank against, so there is nothing to choose between here.
                .orderBy(ranked
                        ? NodePredicateBuilder.searchOrderBy(cb, root, searchPhrase)
                        : NodePredicateBuilder.orderBy(cb, root, sort));

        TypedQuery<FunctionEntity> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        return tq.getResultList();
    }
}
