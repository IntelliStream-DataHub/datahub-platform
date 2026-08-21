// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.jpa.domains.Unit;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.repositories.unit.UnitRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;

@Slf4j
@AllArgsConstructor
@Service
public class UnitService {

    private final UnitRepository unitRepository;

    @Transactional(readOnly = true)
    public Collection<Unit> list(int i) {
        return unitRepository.list(i, Unit.class);
    }

    @Transactional(readOnly = true)
    public Unit findByExternalId(String externalId) {
        return unitRepository.findByExternalId(externalId);
    }

    @Transactional(readOnly = true)
    public Collection<UnitModel> findByIdAndExternalId(Set<Long> idSet, Set<Long> externalIdHashSet) {
        return unitRepository.findByIdAndExternalId(idSet, externalIdHashSet);
    }
}
