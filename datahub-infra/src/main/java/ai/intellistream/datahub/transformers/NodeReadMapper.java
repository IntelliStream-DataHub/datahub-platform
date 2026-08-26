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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public final class NodeReadMapper {

    private NodeReadMapper() {
    }

    /** Map one node to its typed DTO, without relations. */
    public static NodeModel from(NodeEntity node) {
        NodeModel dto = switchOnType(node);
        // Uniform label source, applied last so delegated tails can't diverge. setLabels keeps
        // the type-label present even for a row whose labels string never carried it.
        dto.setLabels(labelsOf(node));
        // The delegated tails each decide their own metadata handling — PolicyTransformer passes a
        // null through, where mapBase always produces a map. Normalise here so one node type does
        // not answer `"metadata": null` while the rest answer `{}`.
        if (dto.getMetadata() == null) {
            dto.setMetadata(new HashMap<>());
        }
        return dto;
    }

    /** Map one node and attach its relations from the edges in hand (no extra queries). */
    public static NodeModel from(NodeEntity node, Collection<EdgeProxy> edges) {
        NodeModel dto = from(node);
        dto.setRelatedResources(RelatedNodeResolver.forNode(dto.getId(), edges));
        return dto;
    }

    public static List<NodeModel> from(Collection<? extends NodeEntity> nodes) {
        List<NodeModel> out = new ArrayList<>(nodes.size());
        for (NodeEntity node : nodes) {
            out.add(from(node));
        }
        return out;
    }

    public static List<NodeModel> from(Collection<? extends NodeEntity> nodes, Collection<EdgeProxy> edges) {
        List<NodeModel> out = new ArrayList<>(nodes.size());
        for (NodeEntity node : nodes) {
            out.add(from(node, edges));
        }
        return out;
    }

    /**
     * Map a Neo4j graph node to its typed DTO, dispatching on the node's labels (the graph
     * carries the same type-labels the rows do). The graph stores only a subset of each node's
     * columns, so a {@code Timeseries} from this path arrives <em>typed but sparsely populated</em>
     * (no unit, no securityCategories) — still strictly better than arriving mistyped as a
     * {@code Resource} carrying an {@code isRoot} that means nothing for it.
     *
     * <p><strong>Constructor defaults are not facts here.</strong> The graph does not store a
     * series' value type or table engine, and {@code Timeseries} seeds both
     * ({@code valueType = float32}, {@code tableEngine = MERGETREE}) with no way to express
     * "unknown" — {@code setValueType(null)} restores the default rather than clearing it. So a
     * BIGINT series read from this path still reports {@code float32}. Treat both fields as
     * unpopulated from a graph read and fetch the node by id for its real values; do not gate
     * numeric behaviour on them. Geometry comes back
     * as the graph's native WGS-84 point reconstructed as a GeoJSON Point (lossy for non-point
     * geometries, which Postgres holds in full), and only on assets.
     */
    public static NodeModel fromGraphNode(org.neo4j.driver.types.Node node) {
        List<String> labels = new ArrayList<>();
        node.labels().forEach(labels::add);
        Set<String> types = ai.intellistream.datahub.jpa.domains.TypeLabels.typeLabelsIn(labels);
        String type = types.isEmpty() ? null : types.iterator().next();

        NodeModel dto;
        if (type == null) {
            Resource resource = new Resource();
            resource.setIsRoot(asBoolean(node, "isRoot"));
            dto = resource;
        } else {
            dto = switch (type) {
                case ai.intellistream.datahub.jpa.domains.TypeLabels.ASSET -> {
                    Asset asset = new Asset();
                    asset.setIsRoot(asBoolean(node, "isRoot"));
                    var geoValue = node.get("geoLocation");
                    if (geoValue != null && !geoValue.isNull()) {
                        var point = geoValue.asPoint();
                        asset.setGeoLocation(new GeoLocation(
                                "{\"type\":\"Point\",\"coordinates\":[" + point.x() + "," + point.y() + "]}"));
                    }
                    yield asset;
                }
                case ai.intellistream.datahub.jpa.domains.TypeLabels.TIMESERIES ->
                        new ai.intellistream.datahub.timeseries.Timeseries();
                case ai.intellistream.datahub.jpa.domains.TypeLabels.DATASET -> new DataSetModel();
                case ai.intellistream.datahub.jpa.domains.TypeLabels.POLICY ->
                        new ai.intellistream.datahub.models.Policy();
                case ai.intellistream.datahub.jpa.domains.TypeLabels.FUNCTION -> new Function();
                default -> new Resource();
            };
        }

        var map = node.asMap();
        dto.setId((Long) map.get("id"));
        dto.setExternalId((String) map.get("externalId"));
        dto.setName((String) map.get("name"));
        dto.setDescription((String) map.get("description"));
        dto.setSource((String) map.get("source"));
        dto.setDataSetId((Long) map.get("dataSetId"));
        dto.setCreatedTime(graphTime(map.get("createdTime")));
        dto.setLastUpdatedTime(graphTime(map.get("lastUpdatedTime")));
        dto.setLabels(labels);
        return dto;
    }

    private static Boolean asBoolean(org.neo4j.driver.types.Node node, String key) {
        var value = node.get(key);
        return (value == null || value.isNull()) ? null : value.asBoolean();
    }

    /** The graph stores epoch millis; older nodes may carry a temporal value instead. */
    private static java.time.ZonedDateTime graphTime(Object value) {
        if (value instanceof Long epoch) {
            return ai.intellistream.datahub.helpers.datetime.DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(epoch);
        }
        if (value instanceof java.time.ZonedDateTime zdt) {
            return zdt;
        }
        return null;
    }

    /**
     * Pick the transformer for this node's type. Nothing is mapped here: each type's conversion
     * lives with that type, so there is one place to look when a field is wrong and one place to
     * change when a field is added.
     */
    private static NodeModel switchOnType(NodeEntity node) {
        if (node instanceof TimeseriesEntity ts) {
            return TimeseriesTransformer.from(ts);
        }
        if (node instanceof PolicyEntity policy) {
            // PolicyTransformer sets no dataSetId, deliberately: Policy.dataSetId is input-only
            // (see POLICY_DATASETID_BUG.md) and a policy row carries no data_set_id anyway.
            return PolicyTransformer.toPolicy(policy);
        }
        if (node instanceof AssetEntity asset) {
            return AssetTransformer.from(asset);
        }
        if (node instanceof DatasetEntity dataset) {
            return DataSetTransformer.from(dataset);
        }
        if (node instanceof FunctionEntity function) {
            return FunctionTransformer.from(function);
        }
        // A plain resource: ResourceTransformer already produces exactly this shape. Its
        // geoLocation and valueType are write-only and @JsonIgnore respectively, so neither
        // reaches a read response even though it sets them for the Pulsar payload.
        return ResourceTransformer.from(node);
    }

    private static List<String> labelsOf(NodeEntity node) {
        String labels = node.getLabels();
        if (labels == null || labels.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(labels.split(",")));
    }
}
