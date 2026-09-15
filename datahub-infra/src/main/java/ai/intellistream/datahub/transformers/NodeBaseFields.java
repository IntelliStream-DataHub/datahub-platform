// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.NodeModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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

    /**
     * A node's labels, from the denormalised {@code labels} column.
     *
     * <p>Never the {@code labelEntities} M2M: it is LAZY, so reading it costs a query per row
     * inside a session and throws {@code LazyInitializationException} outside one — which is what
     * a DTO serialized after the transaction closes does.
     *
     * <p>Shared rather than copied. {@code NodeReadMapper} and {@code FunctionTransformer} each
     * carried their own identical version, and {@code TimeseriesTransformer} carried none at all,
     * which is how a timeseries came to report only its constructor-seeded type-label.
     *
     * <p>The caller passes this to {@code NodeModel.setLabels}, which appends the DTO's type-label
     * if the column does not already carry it — so a row with an empty labels string still reads
     * back correctly typed.
     */
    static List<String> labelsOf(NodeEntity node) {
        String labels = node.getLabels();
        if (labels == null || labels.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(labels.split(",")));
    }
}
