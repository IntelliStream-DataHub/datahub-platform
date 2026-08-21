// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.json.GeoLocationDeserializer;
import ai.intellistream.datahub.json.GeoLocationSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A GeoLocation object conforming to the GeoJSON spec.
 * <p>
 * The geometry is stored verbatim as a raw GeoJSON string in {@link #json}. This single-field
 * shape is deliberate: the enclosing {@link Resource} DTO doubles as a Pulsar Avro payload
 * (reflection-based schema), and arbitrary GeoJSON has variable nesting depth
 * ({@code Point [x,y]} vs {@code Polygon [[[x,y]…]]}) that Avro reflection cannot describe — so
 * the only Avro-safe carrier is an opaque string. On the REST wire Jackson presents it as a
 * nested object via {@link GeoLocationSerializer}/{@link GeoLocationDeserializer}.
 * <p>
 * Storage today is a {@code jsonb} column; the same GeoJSON string migrates cleanly to a PostGIS
 * {@code geography} column later ({@code ST_GeomFromGeoJSON}) with no wire-contract change.
 */
@JsonSerialize(using = GeoLocationSerializer.class)
@JsonDeserialize(using = GeoLocationDeserializer.class)
public class GeoLocation {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The valid GeoJSON geometry {@code type} values. */
    private static final Set<String> GEOMETRY_TYPES = Set.of(
            "Point", "MultiPoint", "LineString", "MultiLineString",
            "Polygon", "MultiPolygon", "GeometryCollection");

    /** The raw GeoJSON geometry, stored verbatim. */
    private String json;

    public GeoLocation() {
    }

    public GeoLocation(String json) {
        this.json = json;
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

    /**
     * Bean-validation gate: a present value must be a structurally valid GeoJSON geometry
     * (a known {@code type} plus {@code coordinates}, or {@code geometries} for a
     * {@code GeometryCollection}). Cascaded from {@code Resource}/{@code ResourceForm} via
     * {@code @Valid}. A null value is valid — the field is optional.
     */
    @JsonIgnore
    @AssertTrue(message = "geolocation.invalid.geojson")
    public boolean isValidGeoJson() {
        if (json == null) {
            return true;
        }
        Map<?, ?> map = parse();
        if (map == null || !(map.get("type") instanceof String type) || !GEOMETRY_TYPES.contains(type)) {
            return false;
        }
        if ("GeometryCollection".equals(type)) {
            return map.get("geometries") instanceof List;
        }
        return map.get("coordinates") instanceof List<?> coords && !coords.isEmpty();
    }

    /** The GeoJSON geometry {@code type} (e.g. {@code "Point"}), or {@code null} if unparseable. */
    @JsonIgnore
    public String geometryType() {
        Map<?, ?> map = parse();
        return map != null && map.get("type") instanceof String type ? type : null;
    }

    /**
     * The {@code [longitude, latitude]} of a {@code Point} geometry, or {@code null} for any other
     * geometry / unparseable value. Used to project a Point onto a native graph point.
     */
    @JsonIgnore
    public double[] pointCoordinates() {
        Map<?, ?> map = parse();
        if (map == null || !"Point".equals(map.get("type"))
                || !(map.get("coordinates") instanceof List<?> coords) || coords.size() < 2
                || !(coords.get(0) instanceof Number lon) || !(coords.get(1) instanceof Number lat)) {
            return null;
        }
        return new double[]{lon.doubleValue(), lat.doubleValue()};
    }

    private Map<?, ?> parse() {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
