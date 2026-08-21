// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.unit;

import ai.intellistream.datahub.jpa.domains.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;

public interface UnitRepository extends JpaRepository<Unit, Integer>, UnitRepo {

    @Query("SELECT u.id FROM Unit u WHERE u.id IN :idList")
    List<Integer> findAllByIdAsIdList(Set<Integer> idList);

    <T> T findByExternalIdHash(Long id, Class<T> type);

}
