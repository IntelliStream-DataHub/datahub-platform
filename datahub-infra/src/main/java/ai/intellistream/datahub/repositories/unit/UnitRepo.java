// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.unit;

import ai.intellistream.datahub.jpa.domains.Unit;
import ai.intellistream.datahub.models.unit.UnitModel;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface UnitRepo {

    <T> Collection<T> list(int maxResults, Class<T> type);

    Collection<UnitModel> findByIdAndExternalId(Set<Long> idSet, Set<Long> externalIdSet);

    <T> List<T> findAllByHashIn(Set<Long> externalIdSet, Class<T> type);

    Unit findByExternalId(String externalId);

}
