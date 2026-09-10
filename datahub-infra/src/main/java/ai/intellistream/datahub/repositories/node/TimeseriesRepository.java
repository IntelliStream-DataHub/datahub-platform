// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.IdCollection;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


public interface TimeseriesRepository extends GenericNodeRepository<TimeseriesEntity>, TimeseriesCustomRepo {

    // ---- Dataset-ACL-narrowed read queries ----------------------------------------------------
    // These push the caller's readable-dataset filter into SQL: only rows whose data_set_id is in
    // the allowed set are returned. Callers must skip these (and use the unfiltered variants) when
    // the caller can read every dataset, and must avoid calling them with an empty `allowed` set.

    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    @Transactional(readOnly = true)
    @Query("SELECT t FROM TimeseriesEntity t WHERE (t.id IN :ids OR t.externalIdHash IN :extIds) "
            + "AND t.dataSet.id IN :allowed")
    List<TimeseriesEntity> findAllByIdInOrExternalIdHashInAndDataSetIdIn(
            @Param("ids") Set<Long> ids,
            @Param("extIds") Set<Long> extIds,
            @Param("allowed") Set<Long> allowed);

    @Transactional(readOnly = true)
    default List<TimeseriesEntity> findAllByIdCollectionAndDataSetIdIn(
            Collection<IdCollection> coll, Set<Long> allowed) {
        Set<Long> ids = coll.stream().map(IdCollection::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> extid = coll.stream().map(IdCollection::getExternalIdHash).filter(Objects::nonNull).collect(Collectors.toSet());
        return findAllByIdInOrExternalIdHashInAndDataSetIdIn(ids, extid, allowed);
    }

    @Transactional(readOnly = true)
    default List<TimeseriesEntity> findAllByIdOrExternalIdAndDataSetIdIn(
            Set<Long> idList, Set<String> externalIdList, Set<Long> allowed) {
        Set<Long> extids = externalIdList.stream()
                .filter(Objects::nonNull)
                .map(ExternalIds::hash)
                .collect(Collectors.toSet());
        return findAllByIdInOrExternalIdHashInAndDataSetIdIn(idList, extids, allowed);
    }

}
