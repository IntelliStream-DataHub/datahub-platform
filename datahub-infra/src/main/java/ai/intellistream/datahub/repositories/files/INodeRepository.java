// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.files;

import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.dto.INodeProxy;
import ai.intellistream.datahub.repositories.node.NodeRepo;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface INodeRepository extends ListCrudRepository<INode, Long>, IINodeRepo {

    @EntityGraph(attributePaths = { "metadata" })
    <T> Optional<T> findByExternalIdHash(Long id, Class<T> type);

    @EntityGraph(attributePaths = { "metadata" })
    <T> Optional<T> findByExternalIdHashAndDeletedAtIsNull(Long id, Class<T> type);

    @EntityGraph(attributePaths = { "metadata" })
    <T> Optional<T> findById(Long id, Class<T> type);

    @EntityGraph(attributePaths = { "metadata" })
    <T> Optional<T> findByIdAndDeletedAtIsNull(Long id, Class<T> type);

    <T> Optional<T> findByPathHash(Long id, Class<T> type);

    <T> Optional<T> findByPathHashAndNodeType(Long id, INode.INodeType nodeType, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "parent" })
    <T> List<T> findAllByParent(INode inode, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "parent", "relatedResources" })
    <T> List<T> findAllByParentAndDeletedAtIsNull(INode inode, Class<T> type);

    @Query("SELECT i.id as id, i.externalId as externalId, i.nodeType as nodeType, i.path as path, i.checksum as checksum, i.parent.id as parentId FROM INode i WHERE i.parent.id = :parentId AND i.deletedAt IS NULL")
    List<INodeProxy> findLiveChildrenOf(long parentId);

    @EntityGraph(attributePaths = { "metadata", "parent" })
    <T> List<T> findAllByParentId(Long parentId, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "parent" })
    @Query("SELECT i FROM INode i WHERE i.parent.pathHash = :parentPathHash")
    <T> List<T> findAllByParentPathHash(@Param("parentPathHash") Long parentPathHash, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "parent", "relatedResources" })
    @Query("SELECT i FROM INode i WHERE i.parent.pathHash = :parentPathHash AND i.deletedAt IS NULL")
    <T> List<T> findAllByParentPathHashAndDeletedAtIsNull(@Param("parentPathHash") Long parentPathHash, Class<T> type);

    @Query(value = "SELECT i FROM INode i WHERE i.pathHash IN ?1")
    <T> List<T> findAllByHashList(Collection<Long> ids, Class<T> type);

    @Modifying(clearAutomatically = true) // clearAutomatically helps avoid stale entities in the persistence context
    @Query("UPDATE INode i SET i.deletedAt = :deletedAt WHERE i.id = :id")
    int markDeleted(long id, ZonedDateTime deletedAt);

    /** Undo a soft delete. The external id was never touched, so the time is all there is to clear. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE INode i SET i.deletedAt = null WHERE i.id = :id")
    int markRestored(long id);

    /**
     * Undo a soft delete performed before V47, which rewrote the external id to a
     * {@code DELETED_<checksum>_<originalId>_<epochMillis>} tombstone. Puts the recovered original
     * id and its hash back alongside clearing the time.
     *
     * <p>Delete once the trash holds no pre-V47 entries; nothing creates them any more.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE INode i SET i.deletedAt = null, i.externalId = :externalId, i.externalIdHash = :hash WHERE i.id = :id")
    int markRestoredFromLegacyTombstone(long id, String externalId, long hash);

    // ---- Dataset-ACL read queries -------------------------------------------------------------
    // Variants used by the file read endpoints when the caller cannot read every dataset. A
    // file/folder is returned when it has NO dataset (public → visible to everyone) OR its
    // data_set_id is in the caller's readable set. An empty `allowed` set still returns the public
    // (no-dataset) rows.

    @EntityGraph(attributePaths = { "metadata" })
    @Query("SELECT i FROM INode i WHERE i.id = :id AND i.deletedAt IS NULL "
            + "AND (i.dataSet IS NULL OR i.dataSet.id IN :allowed)")
    <T> Optional<T> findReadableById(@Param("id") Long id,
                                     @Param("allowed") Collection<Long> allowed, Class<T> type);

    @EntityGraph(attributePaths = { "metadata" })
    @Query("SELECT i FROM INode i WHERE i.externalIdHash = :hash AND i.deletedAt IS NULL "
            + "AND (i.dataSet IS NULL OR i.dataSet.id IN :allowed)")
    <T> Optional<T> findReadableByExternalIdHash(@Param("hash") Long hash,
                                                 @Param("allowed") Collection<Long> allowed, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "parent", "relatedResources" })
    @Query("SELECT i FROM INode i WHERE i.parent IS NULL AND i.deletedAt IS NULL "
            + "AND (i.dataSet IS NULL OR i.dataSet.id IN :allowed)")
    <T> List<T> findReadableInRoot(
                                   @Param("allowed") Collection<Long> allowed, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "parent", "relatedResources" })
    @Query("SELECT i FROM INode i WHERE i.parent.pathHash = :parentPathHash AND i.deletedAt IS NULL "
            + "AND (i.dataSet IS NULL OR i.dataSet.id IN :allowed)")
    <T> List<T> findReadableByParentPathHash(@Param("parentPathHash") Long parentPathHash,
                                             @Param("allowed") Collection<Long> allowed, Class<T> type);

    // Search files AND folders by name (+ description) across the whole tree — PostgreSQL full-text
    // search with a prefix match on the last term, same as the resource/dataset searches. Read the
    // results inside a transaction (native query can't @EntityGraph) so the transformer's lazy
    // metadata/relatedResources load.
    @Query(value = """
            SELECT * FROM inodes
            WHERE deleted_at IS NULL
            AND to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(description,''))
                @@ to_tsquery('simple', cast(websearch_to_tsquery('simple', :q) AS text) || ':*')
            ORDER BY name
            LIMIT :limit
            """, nativeQuery = true)
    List<INode> searchByName(@Param("q") String q, @Param("limit") int limit);

    // Same, narrowed to the caller's readable datasets (public/no-dataset inodes always visible).
    @Query(value = """
            SELECT * FROM inodes
            WHERE deleted_at IS NULL
            AND to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(description,''))
                @@ to_tsquery('simple', cast(websearch_to_tsquery('simple', :q) AS text) || ':*')
            AND (data_set_id IS NULL OR data_set_id IN (:allowed))
            ORDER BY name
            LIMIT :limit
            """, nativeQuery = true)
    List<INode> searchReadableByName(@Param("q") String q,
                                     @Param("allowed") Collection<Long> allowed, @Param("limit") int limit);

    // Resolve the targeted files (full entities, including their nullable dataSet) for write-permission
    // checks on delete. Public (no-dataset) files/folders are deletable by anyone; dataset-bearing
    // ones are checked against the caller's write permissions.
    @Query("SELECT i FROM INode i WHERE (i.id IN :ids OR i.externalIdHash IN :extIds) AND i.deletedAt IS NULL")
    List<INode> findAllByIdOrExternalIdHashAndNotDeleted(@Param("ids") Collection<Long> ids, @Param("extIds") Collection<Long> extIds);

    /**
     * The distinct, non-null dataset ids found anywhere in the subtree(s) rooted at {@code rootIds}
     * — the roots themselves plus every (non-deleted) descendant. Used to enforce write permission
     * across a folder's whole subtree on delete, since deleting a folder cascades to its children.
     */
    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT id, data_set_id FROM inodes WHERE id IN (:rootIds) AND deleted_at IS NULL
                UNION ALL
                SELECT c.id, c.data_set_id FROM inodes c
                JOIN subtree s ON c.parent_id = s.id
                WHERE c.deleted_at IS NULL
            )
            SELECT DISTINCT data_set_id FROM subtree WHERE data_set_id IS NOT NULL
            """, nativeQuery = true)
    List<Long> findSubtreeDataSetIds(@Param("rootIds") Collection<Long> rootIds);

    // ---- Trash view + restore -------------------------------------------------------------------

    /** All soft-deleted nodes of a type (e.g. FILE) — the trash view for a caller who reads everything. */
    @EntityGraph(attributePaths = { "metadata" })
    @Query("SELECT i FROM INode i WHERE i.deletedAt IS NOT NULL AND i.nodeType = :type")
    List<INode> findAllDeletedByNodeType(@Param("type") INode.INodeType type);

    /** Soft-deleted nodes of a type the caller may read (public, or in an allowed dataset). */
    @EntityGraph(attributePaths = { "metadata" })
    @Query("SELECT i FROM INode i WHERE i.deletedAt IS NOT NULL AND i.nodeType = :type "
            + "AND (i.dataSet IS NULL OR i.dataSet.id IN :allowed)")
    List<INode> findReadableDeletedByNodeType(@Param("type") INode.INodeType type, @Param("allowed") Collection<Long> allowed);

    /** Resolve targeted DELETED nodes (full entities incl. dataSet) for the restore write-permission check. */
    @Query("SELECT i FROM INode i WHERE (i.id IN :ids OR i.externalIdHash IN :extIds) AND i.deletedAt IS NOT NULL")
    List<INode> findAllByIdOrExternalIdHashAndDeleted(@Param("ids") Collection<Long> ids, @Param("extIds") Collection<Long> extIds);
}
