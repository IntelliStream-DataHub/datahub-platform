// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.agent;

import ai.intellistream.datahub.jpa.domains.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<AgentEntity, Long> {

    Optional<AgentEntity> findByExternalId(String externalId);

    /**
     * Only the agents that are switched on, which is what every caller that is about to *run*
     * one wants. Ordered by name so a listing reads the same twice.
     */
    List<AgentEntity> findByEnabledTrueOrderByExternalIdAsc();

    List<AgentEntity> findAllByOrderByExternalIdAsc();
}
