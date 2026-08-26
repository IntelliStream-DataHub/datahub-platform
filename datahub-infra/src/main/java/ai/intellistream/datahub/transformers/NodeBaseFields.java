// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.NodeModel;

import java.util.HashMap;
import java.util.Map;

/**
 * The fields every node DTO shares, copied once so each per-type transformer only has to describe
 * what makes its type different.
 *
 * <p>Deliberately not applied by the transformers that deviate from it. {@code PolicyTransformer}
 * leaves {@code dataSetId} unset because a policy's is input-only (POLICY_DATASETID_BUG.md), and
 * {@code TimeseriesTransformer} predates this; both are called directly by their own services, so
 * changing what they map would change those endpoints too. This is for the transformers that want
 * the plain shared shape.
 */
final class NodeBaseFields {

    private NodeBaseFields() {
    }

    static <T extends NodeModel> T apply(T dto, NodeEntity node) {
        dto.setId(node.getId());
        dto.setExternalId(node.getExternalId());
        dto.setName(node.getName());
        dto.setDescription(node.getDescription());
        dto.setSource(node.getSource());
        if (node.getDataSet() != null) {
            dto.setDataSetId(node.getDataSet().getId());
        }
        // A plain copy, so the DTO never aliases Hibernate's PersistentMap — Jackson serializes
        // after the transaction closes and would otherwise hit LazyInitializationException.
        Map<String, String> metadata = node.getMetadata();
        dto.setMetadata(metadata == null ? new HashMap<>() : new HashMap<>(metadata));
        dto.setCreatedTime(node.getDateCreated());
        dto.setLastUpdatedTime(node.getLastUpdated());
        return dto;
    }
}
