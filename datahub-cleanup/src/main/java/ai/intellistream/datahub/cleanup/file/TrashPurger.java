// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.file;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads and permanently deletes trashed inode rows for the CURRENT tenant, using native SQL against
 * the inode tables. Kept in the cleanup module (native SQL, no shared repository/entity/schema
 * change) so the file-purge feature adds nothing to the Postgres schema or the API layer.
 *
 * <p>Routing to the tenant's Postgres is by the caller setting {@code TenantContext} before invoking
 * these — the JPA datasource resolves the tenant when it acquires a connection (same mechanism the
 * other cleanup tasks use).
 */
@Component
@Slf4j
public class TrashPurger {

    /** A trashed inode: its id and its {@code DELETED_..._<epochMillis>} external id — which is also
     *  its filename under the tenant trash folder. */
    public record TrashedNode(long id, String externalId) {}

    @PersistenceContext
    private EntityManager em;

    /** Every soft-deleted inode for the current tenant (uses the existing {@code is_deleted} index). */
    @Transactional(readOnly = true)
    public List<TrashedNode> findTrashed() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, external_id FROM inodes WHERE is_deleted = true").getResultList();
        return rows.stream()
                .map(r -> new TrashedNode(((Number) r[0]).longValue(), (String) r[1]))
                .toList();
    }

    /**
     * Permanently delete one inode row and its OWNED child rows. Native deletes on purpose: a JPA
     * {@code remove} would follow {@code INode.dataSet}'s {@code @OneToOne(CascadeType.ALL)} and
     * delete the SHARED dataset. There is no {@code parent_id} FK, so no cross-row ordering is needed
     * — only a node's own child rows must be removed before its inode row (they carry the FK).
     */
    @Transactional
    public void hardDelete(long id) {
        deleteChildRows("inode_metadata", id);
        deleteChildRows("inode_related_resources", id);
        deleteChildRows("inode_labels", id);
        deleteChildRows("inodes_security_categories", id);
        em.createNativeQuery("DELETE FROM inodes WHERE id = :id").setParameter("id", id).executeUpdate();
    }

    // `table` is one of the fixed constants above — never external input, so string-building is safe.
    private void deleteChildRows(String table, long id) {
        em.createNativeQuery("DELETE FROM " + table + " WHERE inode_id = :id")
                .setParameter("id", id)
                .executeUpdate();
    }
}
