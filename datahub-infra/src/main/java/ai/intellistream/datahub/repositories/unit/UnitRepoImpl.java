// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.unit;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.Unit;
import ai.intellistream.datahub.jpa.dto.NameAndEIdDTO;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.transformers.UnitEntityTransformer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class UnitRepoImpl implements UnitRepo {

    private final String ALIAS_NAMES_REF = "aliasNames";

    @PersistenceContext
    private EntityManager entityManager;

    public UnitRepoImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public <T> Collection<T> list(int maxResults, Class<T> type){
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> q = cb.createQuery(type);

        Root<Unit> root = q.from(Unit.class);
        root.fetch(ALIAS_NAMES_REF, JoinType.LEFT);

        TypedQuery<T> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        return tq.getResultList();
    }

    @Override
    public Collection<UnitModel> findByIdAndExternalId(Set<Long> idSet, Set<Long> externalIdSet) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Unit> q = cb.createQuery(Unit.class);

        List<Predicate> predicates = new ArrayList<>();
        Root<Unit> root = q.from(Unit.class);
        root.fetch(ALIAS_NAMES_REF, JoinType.LEFT);

        if(!idSet.isEmpty()){
            predicates.add( root.get("id").in(idSet) );
        }
        if(!externalIdSet.isEmpty()){
            predicates.add( root.get("externalIdHash").in(externalIdSet) );
        }

        q.where(cb.or(predicates.toArray(new Predicate[0])));

        TypedQuery<Unit> tq = entityManager.createQuery(q);
        var results = tq.getResultList();
        return UnitEntityTransformer.toUnit(results);
    }

    @Override
    public <T> List<T> findAllByHashIn(Set<Long> externalIdSet, Class<T> type) {
        if (externalIdSet.isEmpty()) return new ArrayList<>();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> q = cb.createQuery(type);

        List<Predicate> predicates = new ArrayList<>();
        Root<Unit> root = q.from(Unit.class);
        if (type == Unit.class) {
            root.fetch(ALIAS_NAMES_REF, JoinType.LEFT);
        }

        predicates.add(root.get("externalIdHash").in(externalIdSet));

        q.where(cb.or(predicates.toArray(new Predicate[0])));

        if (type == NameAndEIdDTO.class) {
            q.select(cb.construct(type, root.get("id"), root.get("name"), root.get("externalId")));
        }

        TypedQuery<T> tq = entityManager.createQuery(q);
        var results = tq.getResultList();
        return results;
    }

    @Override
    public Unit findByExternalId(String externalId) {
        externalId = TextValidator.toSnakeLowerCasedAllowStartWithDigits(externalId);
        Set<Long> externalIds = Set.of(IdGenerator.xxHash(externalId));
        return findAllByHashIn(externalIds, Unit.class).stream().findFirst().orElse(null);
    }
}
