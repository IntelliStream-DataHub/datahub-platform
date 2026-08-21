// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.files;

import ai.intellistream.datahub.jpa.dto.INodeProxy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
public class IINodeRepoImpl implements IINodeRepo {

    @PersistenceContext
    private EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;

    public IINodeRepoImpl(
            EntityManager entityManager,
            EntityManagerFactory entityManagerFactory
    ) {
        this.entityManager = entityManager;
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public List<INodeProxy> findAllByIdAndExternalIdAndNotDeleted(Set<Long> idList, Set<String> externalIdList) {

        if(externalIdList.isEmpty() && idList.isEmpty()){
            return new ArrayList<>();
        }

        String sql;
        boolean hasIds = !idList.isEmpty();
        boolean hasExternalIds = !externalIdList.isEmpty();

        if (hasIds && hasExternalIds) {
            sql = """
            SELECT id, external_id, node_type, path, checksum, parent_id FROM inodes t1
            WHERE t1.id IN (:ids) AND t1.is_deleted IS false
            UNION
            SELECT id, external_id, node_type, path, checksum, parent_id FROM inodes t2
            WHERE t2.external_id_hash IN (:exIds) AND t2.is_deleted IS false
        """;
        } else if (hasIds) {
            sql = """
            SELECT id, external_id, node_type, path, checksum, parent_id FROM inodes t1
            WHERE t1.id IN (:ids) AND t1.is_deleted IS false
        """;
        } else { // hasExternalIds
            sql = """
            SELECT id, external_id, node_type, path, checksum, parent_id FROM inodes t1
            WHERE t1.external_id_hash IN (:exIds) AND t1.is_deleted IS false
        """;
        }

        Session session = entityManager.unwrap(Session.class);
        NativeQuery<INodeProxy> nq = session.createNativeQuery(sql, INodeProxy.class);
        nq.setReadOnly(true);
        if(!idList.isEmpty()){
            nq.setParameter("ids", idList);
        }
        if(!externalIdList.isEmpty()){
            List<Long> externalIdHashList = externalIdList.stream().map( it -> LongHashFunction.xx3().hashChars(it)).toList();
            nq.setParameter("exIds", externalIdHashList);
        }
        var results = nq.getResultList();
        return results;
    }

    @Override
    public void flush() {
        entityManager.flush();
    }

}
