// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.GeoLocation;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one entity→DTO path for typed node reads: any {@link NodeEntity} maps to the DTO its
 * discriminator names ({@code AssetEntity}→{@link Asset}, …, plain {@code ResourceEntity}→
 * {@link Resource}), so a mixed result set comes back typed instead of flattened into one
 * lossy {@code Resource}. Cross-cutting decisions live here once:
 *
 * <ul>
 *   <li><b>Labels come from the denormalised string</b> on the row, for every type — never the
 *       LAZY {@code labelEntities} M2M (a query per row inside a session, a
 *       {@code LazyInitializationException} outside). This also fixes the timeseries read that
 *       reported only the constructor-seeded type-label and dropped every domain label.</li>
 *   <li><b>Metadata is copied into a plain {@code HashMap}</b> so the DTO never aliases
 *       Hibernate's {@code PersistentMap} (Jackson serializes after the transaction).</li>
 *   <li><b>{@code geoLocation} is populated only for assets</b> — the only entity with the
 *       column — which is what keeps the other shapes clean without runtime guards.</li>
 * </ul>
 *
 * Type-specific tails delegate to the existing transformers where one exists
 * ({@link TimeseriesTransformer}, {@link PolicyTransformer}) so their behaviour is not
 * re-derived. A component rather than a static family so per-type strategies can be injected
 * later; it does no I/O.
 *
 * <p>{@link ResourceTransformer} deliberately stays untouched beside this: it feeds the Pulsar
 * {@code ResourceCudMessage}, whose Avro reflection schema cannot carry a polymorphic union.
 */
@Component
public class NodeReadMapper {

    /** Map one node to its typed DTO, without relations. */
    public NodeModel from(NodeEntity node) {
        NodeModel dto = switchOnType(node);
        // Uniform label source, applied last so delegated tails can't diverge. setLabels keeps
        // the type-label present even for a row whose labels string never carried it.
        dto.setLabels(labelsOf(node));
        return dto;
    }

    /** Map one node and attach its relations from the edges in hand (no extra queries). */
    public NodeModel from(NodeEntity node, Collection<EdgeProxy> edges) {
        NodeModel dto = from(node);
        dto.setRelatedResources(RelatedNodeResolver.forNode(dto.getId(), edges));
        return dto;
    }

    public List<NodeModel> from(Collection<? extends NodeEntity> nodes) {
        List<NodeModel> out = new ArrayList<>(nodes.size());
        for (NodeEntity node : nodes) {
            out.add(from(node));
        }
        return out;
    }

    public List<NodeModel> from(Collection<? extends NodeEntity> nodes, Collection<EdgeProxy> edges) {
        List<NodeModel> out = new ArrayList<>(nodes.size());
        for (NodeEntity node : nodes) {
            out.add(from(node, edges));
        }
        return out;
    }

    private NodeModel switchOnType(NodeEntity node) {
        if (node instanceof TimeseriesEntity ts) {
            return TimeseriesTransformer.from(ts);
        }
        if (node instanceof PolicyEntity policy) {
            // PolicyTransformer sets no dataSetId, deliberately: Policy.dataSetId is input-only
            // (see POLICY_DATASETID_BUG.md) and a policy row carries no data_set_id anyway.
            return PolicyTransformer.toPolicy(policy);
        }
        if (node instanceof AssetEntity asset) {
            Asset dto = mapBase(new Asset(), asset);
            dto.setIsRoot(asset.getIsRoot());
            if (asset.getGeoLocation() != null) {
                dto.setGeoLocation(new GeoLocation(asset.getGeoLocation()));
            }
            return dto;
        }
        if (node instanceof DatasetEntity dataset) {
            // DataSetModel's extras (policies, connectedDataSets) are input-only; empty on reads.
            return mapBase(new DataSetModel(), dataset);
        }
        if (node instanceof FunctionEntity function) {
            // Not FunctionTransformer.toFunction: it reads the LAZY labelEntities M2M.
            return mapBase(new Function(), function);
        }
        Resource dto = mapBase(new Resource(), node);
        dto.setIsRoot(node.getIsRoot());
        return dto;
    }

    private <T extends NodeModel> T mapBase(T dto, NodeEntity node) {
        dto.setId(node.getId());
        dto.setExternalId(node.getExternalId());
        dto.setName(node.getName());
        dto.setDescription(node.getDescription());
        dto.setSource(node.getSource());
        if (node.getDataSet() != null) {
            dto.setDataSetId(node.getDataSet().getId());
        }
        Map<String, String> meta = node.getMetadata();
        dto.setMetadata(meta == null ? new HashMap<>() : new HashMap<>(meta));
        dto.setCreatedTime(node.getDateCreated());
        dto.setLastUpdatedTime(node.getLastUpdated());
        return dto;
    }

    private static List<String> labelsOf(NodeEntity node) {
        String labels = node.getLabels();
        if (labels == null || labels.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(labels.split(",")));
    }
}
