// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.governance;

import ai.intellistream.datahub.jpa.domains.GovernanceTemplate;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GovernanceTemplateRepository extends JpaRepository<GovernanceTemplate, Long> {

    // Fetch all governance templates with metadata in a single query.
    @EntityGraph(attributePaths = { "metadata" })
    List<GovernanceTemplate> findAll();

    // Fetch a single governance template with metadata by ID.

    @EntityGraph(attributePaths = { "metadata" })
    Optional<GovernanceTemplate> findById(Long id);
}
