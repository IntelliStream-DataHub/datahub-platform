// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.RelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipTypeRepository extends JpaRepository<RelationshipType, Long> {

    RelationshipType findByHash(Long id);
}
