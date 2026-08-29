// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services.graph;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import ai.intellistream.datahub.helpers.text.Labels;
import ai.intellistream.datahub.models.GeoLocation;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.getTableType;

/**
 * Projects a persisted entity onto the property map its graph node or relationship should carry.
 *
 * <p>This is the whole mapping layer between the two stores, and it is written to be exhaustive:
 * the applier replaces a node's properties wholesale with what comes out of here, so a field
 * omitted here is a field the graph does not have. Property <em>names</em> are load-bearing —
 * readers match on {@code id} and read {@code metadata_}-prefixed keys back through
 * {@link ai.intellistream.datahub.transformers.EdgeProxyTransformer} — so the names below are
 * kept exactly as the Pulsar-era listener wrote them, and new fields are added alongside.
 */
final class GraphNodeProperties {

    private static final Logger log = LoggerFactory.getLogger(GraphNodeProperties.class);

    private GraphNodeProperties() {}

    /**
     * Every property a node should carry. Null values are included deliberately: the applier
     * assigns the whole map, and Neo4j drops a property assigned null, which is how a value
     * cleared in Postgres stops existing in the graph.
     */
    static Map<String, Object> of(NodeEntity node) {
        Map<String, Object> props = new HashMap<>();
        props.put("id", node.getId());
        props.put("externalId", node.getExternalId());
        props.put("name", node.getName());
        props.put("description", node.getDescription());
        props.put("source", node.getSource());
        props.put("isRoot", node.getIsRoot());
        props.put("isDeactivated", node.isDeactivated());
        props.put("dataSetId", node.getDataSet() == null ? null : node.getDataSet().getId());
        // Written as native temporals, not strings, so Cypher can compare and order on them.
        props.put("createdTime", node.getDateCreated());
        props.put("lastUpdatedTime", node.getLastUpdated());

        Map<String, String> metadata = node.getMetadata();
        if (metadata != null) {
            metadata.forEach((key, value) -> props.put("metadata_" + key, value));
        }

        if (node instanceof AssetEntity asset && asset.getGeoLocation() != null) {
            Object point = pointOrNull(new GeoLocation(asset.getGeoLocation()));
            props.put("geoLocation", point);
        }
        if (node instanceof TimeseriesEntity ts) {
            props.put("valueType", ts.getValueType() == null ? null : getTableType(ts.getValueType()));
            props.put("unit", ts.getUnit());
            props.put("unitExternalId", ts.getUnitExternalId());
            props.put("tableEngine", ts.getTableEngine() == null ? null : ts.getTableEngine().name());
        }
        return props;
    }

    /**
     * The labels a node should carry: its user labels plus the type-label its concrete entity class
     * dictates, each in the one canonical form the rest of the platform uses.
     *
     * <p>Canonicalised through {@link Labels#canonical} rather than trusted as stored, and through
     * that method specifically. It is what {@code Label.setName} applies on persist and what
     * {@code NodeFilter} reproduces to match label hashes, so a second spelling here would put
     * labels in the graph that no query for them could find. The columns feeding this are not all
     * written through that path, so a {@code rotating-equipment} can reach here; it belongs in the
     * graph as {@code ROTATING_EQUIPMENT}.
     *
     * <p>The previous writer merely deleted dashes, which is neither canonical nor reversible.
     * Nodes it wrote converge on the canonical spelling the next time they are touched.
     */
    static Set<String> labelsOf(NodeEntity node) {
        Set<String> labels = new LinkedHashSet<>();
        TypeLabels.forEntity(node).ifPresent(labels::add);
        if (node.getLabels() != null && !node.getLabels().isBlank()) {
            Arrays.stream(node.getLabels().split(","))
                    .map(Labels::canonical)
                    .filter(label -> label != null && !label.isBlank())
                    .forEach(labels::add);
        }
        return labels;
    }

    static Map<String, Object> of(EdgeEntity edge) {
        Map<String, Object> props = new HashMap<>();
        props.put("id", edge.getId());
        props.put("start", edge.getStart());
        props.put("end", edge.getEnd());
        props.put("typeId", edge.getRelationshipType() == null ? null : edge.getRelationshipType().getId());
        props.put("description", edge.getDescription());
        // Nodes carry the data set they belong to; edges did not, so an edge read back from the
        // graph could not say which data set scoped it without going to Postgres for it.
        props.put("dataSetId", edge.getDataSet() == null ? null : edge.getDataSet().getId());
        props.put("createdTime", edge.getDateCreated());
        props.put("lastUpdatedTime", edge.getLastUpdated());
        if (edge.getMetadata() != null) {
            edge.getMetadata().forEach((key, value) -> props.put("metadata_" + key, value));
        }
        return props;
    }

    /**
     * A native WGS-84 (SRID 4326) point, so {@code point.distance(...)} returns geodesic metres —
     * or null for a missing or non-point geometry. Postgres keeps the full GeoJSON; the graph
     * carries only a point for distance queries, so geometries needing a centroid are deferred.
     */
    private static Object pointOrNull(GeoLocation geo) {
        double[] coords = geo.pointCoordinates();
        if (coords == null) {
            log.info("Skipping graph geolocation for non-point geometry '{}'", geo.geometryType());
            return null;
        }
        return Values.point(4326, coords[0], coords[1]);
    }

    /**
     * A label as it must appear inside Cypher: backtick-quoted, with any internal backtick doubled.
     *
     * <p>Quoting rather than restricting. Label canonicalisation is unicode-aware, so {@code MÅLER}
     * is an ordinary stored label here, and older graphs can hold labels with characters no
     * canonicalisation would produce today. An identifier whitelist would drop those on the way in
     * and, because labels are also removed by name, strip them from nodes that already carry them.
     */
    static String escape(String label) {
        return "`" + label.replace("`", "``") + "`";
    }
}
