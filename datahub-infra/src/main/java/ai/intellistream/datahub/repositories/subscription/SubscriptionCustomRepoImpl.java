// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.subscription.SubscriptionFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public class SubscriptionCustomRepoImpl implements SubscriptionCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionEntity> filter(SubscriptionFilter filter, int maxResults,
                                           SubscriptionSort sort, PageCursor cursor) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<SubscriptionEntity> q = cb.createQuery(SubscriptionEntity.class);
        Root<SubscriptionEntity> root = q.from(SubscriptionEntity.class);

        List<Predicate> predicates = SubscriptionPredicateBuilder.build(cb, q, root, filter);
        if (cursor != null) {
            predicates.add(SubscriptionPredicateBuilder.keyset(cb, root, sort, cursor));
        }

        // No DISTINCT: the timeseries criterion is an EXISTS subquery rather than a join, so
        // nothing here returns a subscription more than once. See SubscriptionPredicateBuilder.
        q.select(root)
                .where(predicates.toArray(new Predicate[0]))
                .orderBy(SubscriptionPredicateBuilder.orderBy(cb, root, sort));

        TypedQuery<SubscriptionEntity> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        return tq.getResultList();
    }
}
