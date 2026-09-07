// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, Long>, SubscriptionCustomRepo {

    boolean existsByExternalIdHash(Long externalIdHash);

    Optional<SubscriptionEntity> findByExternalIdHash(Long externalIdHash);

    Set<SubscriptionEntity> findAllByIdInOrExternalIdHashIn(Set<Long> ids, Set<Long> externalIdHashes);

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
}
