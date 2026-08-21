// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

@Slf4j
public class EdgeRepoImpl implements EdgeRepo {

    @PersistenceContext
    private EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;


    public EdgeRepoImpl(
            EntityManager entityManager,
            EntityManagerFactory entityManagerFactory
    ) {
        this.entityManager = entityManager;
        this.entityManagerFactory = entityManagerFactory;
    }

    @Transactional
    public void deleteAllByNodeIds(Collection<Long> idList){
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaDelete<EdgeEntity> delete = cb.createCriteriaDelete(EdgeEntity.class);
        Root<EdgeEntity> root = delete.from(EdgeEntity.class);

        Expression<String> startExpression = root.get("start");
        Predicate predicateForA = startExpression.in(idList);

        Expression<String> endExpr = root.get("end");
        Predicate predicateForB = endExpr.in(idList);

        Predicate combinedPredicate = cb.or(predicateForA, predicateForB);
        delete.where(combinedPredicate);

        int rowsDeleted = entityManager.createQuery(delete).executeUpdate();
        log.debug("Edges connected to timeseries node deleted: " + rowsDeleted);
    }
}
