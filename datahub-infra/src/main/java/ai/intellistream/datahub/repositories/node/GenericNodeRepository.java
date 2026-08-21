// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.IdCollection;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

public interface GenericNodeRepository<T extends NodeEntity> extends JpaRepository<T, Long> {
    <U> List<U> findAllByExternalIdHashIn(List<Long> idList, Class<U> type);

    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    List<T> findAllByExternalIdHashIn(List<Long> idList);


    default List<T> findAllByExternalIdIn(List<String> externalIds){
        List<Long> ids = externalIds.stream().map(ExternalIds::hash).collect(Collectors.toList());
        return findAllByExternalIdHashIn(ids);
    }

    <U> Optional<U> findById(@NotNull Long id, Class<U> type);


    <U> List<U> findAllByIdIn(Collection<Long> id, Class<U> type);
    boolean existsByExternalIdHashIn(Collection<Long> hashlist);
    boolean existsByExternalIdHash(Long hash);

    @Transactional(readOnly = true)
    default Optional<T> findByIdCollection(@Param("coll") IdCollection coll){
        return findByIdOrExternalId(coll.getId(), coll.getExternalId());
    }

    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    @Transactional(readOnly = true)
    Optional<T> findByIdOrExternalId(Long id, String externalId);

    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    @Transactional(readOnly = true)
    List<T> findAllByIdInOrExternalIdHashIn(Set<Long> idList, Set<Long> externalIdList);

    @Transactional(readOnly = true)
     <U> List<U> findAllByIdInOrExternalIdHashIn(Set<Long> idList, Set<Long> externalIdList, Class<U> type);

    @Transactional(readOnly = true)
     default <U> List<U> findAllByIdOrExternalId(Set<Long> idList, Set<String> externalIdList,Class<U> type){
        Set<Long> extids = externalIdList.stream()
                .filter(Objects::nonNull)
                .map(ExternalIds::hash)
                .collect(Collectors.toSet());
        return findAllByIdInOrExternalIdHashIn(idList, extids, type);
    }

    @Transactional(readOnly = true)
    default List<T> findAllByIdOrExternalId(Set<Long> idList, Set<String> externalIdList){
        Set<Long> extids = externalIdList.stream()
                .filter(Objects::nonNull)
                .map(ExternalIds::hash)
                .collect(Collectors.toSet());
        return findAllByIdInOrExternalIdHashIn(idList, extids);
    }
    @Transactional(readOnly = true)
    default List<T> findAllByIdCollection(@NotNull @NotEmpty Collection<IdCollection> coll){
        Set<Long> ids = coll.stream().map(IdCollection::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> extid = coll.stream().map(IdCollection::getExternalIdHash).filter(Objects::nonNull).collect(Collectors.toSet());
        return findAllByIdInOrExternalIdHashIn(ids, extid);
    }
    @Transactional(readOnly = true)
    default <U>  List<U> findAllByIdCollection(@NotNull @NotEmpty Collection<IdCollection> coll,Class<U> type){
        Set<Long> ids = coll.stream().map(IdCollection::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> extid = coll.stream().map(IdCollection::getExternalIdHash).filter(Objects::nonNull).collect(Collectors.toSet());
        return findAllByIdInOrExternalIdHashIn(ids, extid, type);
    }
    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    T findByExternalId(String id);

    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    T findByExternalIdHash(Long id);

    @Modifying
    @Transactional
    @Query("DELETE FROM NodeEntity ne WHERE ne.id in ?1")
    void deleteWithIds(List<Long> ids);

    /**
     * Acquire a {@code SELECT ... FOR UPDATE} lock on the rows with the given ids.
     * <p>
     * Used at the start of delete flows to close a TOCTOU window: Postgres' FK check on
     * an incoming {@code INSERT INTO edge (rel_start/rel_end)} takes a {@code FOR KEY SHARE}
     * lock on the referenced node row, which conflicts with this {@code FOR UPDATE}. A
     * concurrent edge-create therefore blocks until the delete commits, at which point its
     * FK validation sees the row missing and fails cleanly — instead of the delete failing
     * with a surprising FK violation because a new edge was inserted between the
     * application-level edge check and the node delete.
     * <p>
     * Must be called inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM NodeEntity n WHERE n.id IN :ids")
    List<NodeEntity> lockByIdIn(@Param("ids") Collection<Long> ids);

    @EntityGraph(attributePaths = { "metadata" })
    <U> Optional<U> findByName(String name, Class<U> type);

    List<T> findAllByName(String name);
    @EntityGraph(attributePaths = { "metadata" })
    <U> List<U> findAllByName(String name, Class<U> type);

    @Query("SELECT n.id FROM NodeEntity n WHERE n.id IN :idList")
    List<Long> findAllByIdAsIdList(Set<Long> idList);

    @Transactional(readOnly = true)
    <U> U findByExternalIdHash(Long id, Class<U> type);


}
