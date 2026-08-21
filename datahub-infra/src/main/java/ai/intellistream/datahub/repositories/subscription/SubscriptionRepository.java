// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, Long> {

    boolean existsByExternalIdHash(Long externalIdHash);

    Optional<SubscriptionEntity> findByExternalIdHash(Long externalIdHash);

    Set<SubscriptionEntity> findAllByIdInOrExternalIdHashIn(Set<Long> ids, Set<Long> externalIdHashes);

    List<SubscriptionEntity> findAllBySystemManagedFalse(Pageable pageable);

    /**
     * List subscriptions bound to any timeseries in the supplied id/externalIdHash sets.
     * {@code SELECT DISTINCT} is required because the {@code @ManyToMany} join can otherwise
     * return the same subscription once per matching timeseries.
     */
    @Query("SELECT DISTINCT s FROM SubscriptionEntity s JOIN s.timeseries t " +
            "WHERE t.id IN :ids OR t.externalIdHash IN :hashes")
    List<SubscriptionEntity> findAllByTimeseriesIdInOrTimeseriesExternalIdHashIn(
            @Param("ids") Set<Long> timeseriesIds,
            @Param("hashes") Set<Long> timeseriesExternalIdHashes,
            Pageable pageable);

    @Query("SELECT DISTINCT s FROM SubscriptionEntity s JOIN s.timeseries t WHERE t.id IN :ids")
    List<SubscriptionEntity> findAllByTimeseriesIdIn(@Param("ids") Set<Long> timeseriesIds);

    /**
     * The dataset id of every timeseries bound to the subscription with the given external-id hash.
     * The {@code LEFT JOIN} on the dataset means an orphan timeseries (no dataset) contributes a
     * {@code null} element — callers enforcing a dataset ACL must treat {@code null} as "orphan"
     * (readable only by an all-datasets reader). One element per bound timeseries; empty when the
     * subscription has none (or does not exist).
     */
    @Query("SELECT ds.id FROM SubscriptionEntity s JOIN s.timeseries t LEFT JOIN t.dataSet ds " +
            "WHERE s.externalIdHash = :hash")
    List<Long> findTimeseriesDatasetIds(@Param("hash") long externalIdHash);

    /**
     * Variant of {@link #findAllByTimeseriesIdInOrTimeseriesExternalIdHashIn(Set, Set, Pageable)}
     * that drops system-managed rows. Used by the public list endpoint when the caller has not
     * opted in via {@code includeSystemManaged}.
     */
    @Query("SELECT DISTINCT s FROM SubscriptionEntity s JOIN s.timeseries t " +
            "WHERE s.systemManaged = false AND (t.id IN :ids OR t.externalIdHash IN :hashes)")
    List<SubscriptionEntity> findAllUserManagedByTimeseriesIdInOrTimeseriesExternalIdHashIn(
            @Param("ids") Set<Long> timeseriesIds,
            @Param("hashes") Set<Long> timeseriesExternalIdHashes,
            Pageable pageable);
}
