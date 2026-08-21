// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public interface NodeRepository extends GenericNodeRepository<NodeEntity>, NodeRepo {

    // ---- Dataset-ACL-narrowed read query ------------------------------------------------------
    // Resolve nodes by id/externalId, returning only those whose data_set_id is in `allowed`.
    // Used by the resource read endpoints when the caller cannot read every dataset. Callers must
    // avoid an empty `allowed` set (no readable datasets → return nothing without querying).

    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    @Transactional(readOnly = true)
    @Query("SELECT n FROM NodeEntity n WHERE (n.id IN :ids OR n.externalIdHash IN :extIds) "
            + "AND n.dataSet.id IN :allowed")
    List<NodeEntity> findAllByIdInOrExternalIdHashInAndDataSetIdIn(
            @Param("ids") Set<Long> ids,
            @Param("extIds") Set<Long> extIds,
            @Param("allowed") Set<Long> allowed);

    @Transactional(readOnly = true)
    default List<NodeEntity> findAllByIdOrExternalIdAndDataSetIdIn(
            Set<Long> idList, Set<String> externalIdList, Set<Long> allowed) {
        Set<Long> extids = externalIdList.stream()
                .filter(Objects::nonNull)
                .map(ExternalIds::hash)
                .collect(Collectors.toSet());
        return findAllByIdInOrExternalIdHashInAndDataSetIdIn(idList, extids, allowed);
    }
}
