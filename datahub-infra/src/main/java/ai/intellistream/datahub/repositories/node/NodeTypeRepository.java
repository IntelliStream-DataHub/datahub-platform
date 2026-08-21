// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.NodeType;
import org.springframework.data.jpa.repository.JpaRepository;


public interface NodeTypeRepository extends JpaRepository<NodeType, Long> {

}
