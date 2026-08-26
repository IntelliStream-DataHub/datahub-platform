// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.*;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdAndType;
import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.enums.TableEngine;
import ai.intellistream.datahub.transformers.TimeseriesTransformer;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class NodeRepoImpl implements NodeRepo {

    @PersistenceContext
    private EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;

    public NodeRepoImpl(
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
        Root<NodeEntity> root = q.from(NodeEntity.class);
        setFetches(root);
        q.from(type);
        TypedQuery<T> query = entityManager.createQuery(q);
        query.setMaxResults(maxResults);
        return query.getResultList();*/

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> q = cb.createQuery(type);
        Root<NodeEntity> root = q.from(NodeEntity.class);
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
        Root<NodeEntity> root = q.from(NodeEntity.class);
        q.where(cb.equal(root.get("nodeType"), nodeType));
        setFetches(root);
        Order orderDesc = cb.desc(root.get("dateCreated"));
        q.orderBy(orderDesc);
        TypedQuery<T> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        var r = tq.getResultList();
        return r;
    }

    private static void setFetches(Root<NodeEntity> root) {
        root.fetch(METADATA_REF, JoinType.LEFT);
        Fetch<NodeEntity, AssetEntity> asset = root.fetch("asset", JoinType.LEFT);
        Fetch<NodeEntity, TimeseriesEntity> ts = root.fetch("timeseries", JoinType.LEFT);
        Fetch<NodeEntity, FunctionEntity> f = root.fetch("functionEntity", JoinType.LEFT);
        ts.fetch(VAL_TYPE_REF, JoinType.LEFT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NodeEntity> list(int maxResults) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<NodeEntity> q = cb.createQuery(NodeEntity.class);
        Root<NodeEntity> root = q.from(NodeEntity.class);
        setFetches(root);
        Order orderDesc = cb.desc(root.get("dateCreated"));
        q.select(root).orderBy(orderDesc);
        TypedQuery<NodeEntity> tq = entityManager.createQuery(q);
        tq.setMaxResults(maxResults);
        var r = tq.getResultList();
        return r;
    }


}
