// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public class DataSetCustomRepoImpl implements DataSetCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<DatasetEntity> filter(DataSetFilter filter, int maxResults) {
        return filter(filter, maxResults, NodeSort.DEFAULT, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasetEntity> filter(DataSetFilter filter, int maxResults, NodeSort sort, PageCursor cursor) {
        return search(null, filter, maxResults, sort, cursor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasetEntity> search(String searchPhrase, DataSetFilter filter, int maxResults,
                                      NodeSort sort, PageCursor cursor) {
        DataSetFilter criteria = filter != null ? filter : new DataSetFilter();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<DatasetEntity> q = cb.createQuery(DatasetEntity.class);
        Root<DatasetEntity> root = q.from(DatasetEntity.class);

        // Every criterion a data set can be filtered by is now a shared node criterion, so this
        // method is the builder plus a discriminator. The write-protected/deactivated flags were
        // the only data-set-specific ones, and they are gone as inert.
        List<Predicate> predicates = NodePredicateBuilder.build(cb, q, root, criteria, NodeType.DATASET);

        if (searchPhrase != null && !searchPhrase.isBlank()) {
            predicates.add(NodePredicateBuilder.fullTextMatch(cb, root, searchPhrase));
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
                // A search orders by relevance; a filter by the caller's sort. A filter has no
                // phrase to rank against, so there is nothing to choose between here.
                .orderBy(ranked
                        ? NodePredicateBuilder.searchOrderBy(cb, root, searchPhrase)
                        : NodePredicateBuilder.orderBy(cb, root, sort));

        TypedQuery<DatasetEntity> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        return tq.getResultList();
    }
}
