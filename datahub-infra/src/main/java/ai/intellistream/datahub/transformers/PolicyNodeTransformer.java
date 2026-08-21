// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.PolicyNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PolicyNodeTransformer {

    public static PolicyNode toPolicyNode(NodeEntity node) {
        PolicyNode dto = new PolicyNode();

        dto.setId(node.getId());
        dto.setName(node.getName());
        dto.setExternalId(node.getExternalId());
        dto.setNodeType(node.getNodeType() != null ? node.getNodeType().getName() : "POLICY");
        dto.setCreatedAt(node.getDateCreated());
        dto.setLastUpdated(node.getLastUpdated());
        dto.setMetadata(node.getMetadata());
        return dto;
    }

    public static List<PolicyNode> toPolicyNodes(List<NodeEntity> nodes) {
        return nodes.stream()
                .map(PolicyNodeTransformer::toPolicyNode)
                .toList();
    }
}
