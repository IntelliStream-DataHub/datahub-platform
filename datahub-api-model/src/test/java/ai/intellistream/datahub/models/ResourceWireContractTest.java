// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pins the exact on-the-wire shape of {@link Resource} (field set + serialized values), order-independent.
 * This is the contract net for the {@code NodeModel} hoist: re-parenting {@code Resource} onto a shared base
 * must leave this green — i.e. the JSON is byte-equivalent (modulo key order, which JSON consumers ignore).
 */
class ResourceWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @SuppressWarnings("unchecked")
    void resourceWireShapeIsStable() {
        Resource r = new Resource();
        r.setId(34L);
        r.setExternalId("sensor_a");
        r.setName("Sensor A");
        r.setIsRoot(true);
        r.setDescription("desc");
        r.setDataSetId(12L);
        r.setSource("src");
        r.setLabels(List.of("resource", "PIPE"));
        r.setMetadata(Map.of("k", "v"));
        r.setCreatedTime(ZonedDateTime.parse("2024-06-17T12:34:56Z"));
        r.setLastUpdatedTime(ZonedDateTime.parse("2024-06-18T00:00:00Z"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(r), Map.class);

        assertEquals("34", m.get("id"));            // ToStringSerializer
        assertEquals("sensor_a", m.get("externalId"));
        assertEquals("Sensor A", m.get("name"));
        assertEquals(true, m.get("isRoot"));
        assertEquals("desc", m.get("description"));
        assertEquals("12", m.get("dataSetId"));     // ToStringSerializer
        assertEquals("src", m.get("source"));
        assertEquals(List.of("resource", "PIPE"), m.get("labels"));
        assertEquals(Map.of("k", "v"), m.get("metadata"));
        assertEquals(List.of(), m.get("relatedResources"));
        assertEquals("2024-06-17T12:34:56Z", m.get("createdTime"));
        assertEquals("2024-06-18T00:00:00Z", m.get("lastUpdatedTime"));

        // @JsonIgnore'd / null-omitted fields must not surface.
        assertFalse(m.containsKey("elementId"));
        assertFalse(m.containsKey("valueType"));
        assertFalse(m.containsKey("hashedLabels"));
        assertFalse(m.containsKey("geoLocation"));

        // Exact key set (order-independent) — nothing added or dropped.
        assertEquals(Set.of("id", "externalId", "name", "isRoot", "relatedResources", "metadata",
                "description", "dataSetId", "source", "labels", "createdTime", "lastUpdatedTime"),
                m.keySet());
    }

    /**
     * A present geolocation serializes as a nested GeoJSON object (not a quoted string) and
     * round-trips back to the same raw geometry. Covers the Avro-safe String carrier being bridged
     * to a REST object by the custom serializer/deserializer.
     */
    @Test
    @SuppressWarnings("unchecked")
    void geoLocationRoundTripsAsNestedObject() {
        Resource r = new Resource();
        r.setExternalId("sensor_a");
        r.setName("Sensor A");
        r.setGeoLocation(new GeoLocation("{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}"));

        String json = mapper.writeValueAsString(r);
        Map<String, Object> m = mapper.readValue(json, Map.class);

        // Nested object on the wire, never an escaped string.
        assertInstanceOf(Map.class, m.get("geoLocation"));
        Map<String, Object> geo = (Map<String, Object>) m.get("geoLocation");
        assertEquals("Point", geo.get("type"));
        assertEquals(List.of(10.75, 59.91), geo.get("coordinates"));

        // Deserialize back and confirm the geometry survives verbatim.
        Resource back = mapper.readValue(json, Resource.class);
        Map<String, Object> backGeo = mapper.readValue(back.getGeoLocation().getJson(), Map.class);
        assertEquals("Point", backGeo.get("type"));
        assertEquals(List.of(10.75, 59.91), backGeo.get("coordinates"));
    }

    /** A polygon (general geometry, not just Point) survives the same round-trip. */
    @Test
    @SuppressWarnings("unchecked")
    void geoLocationAcceptsGeneralGeometry() {
        Resource r = new Resource();
        r.setExternalId("area_a");
        r.setName("Area A");
        r.setGeoLocation(new GeoLocation(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(r), Map.class);
        Map<String, Object> geo = (Map<String, Object>) m.get("geoLocation");
        assertEquals("Polygon", geo.get("type"));
        assertInstanceOf(List.class, geo.get("coordinates"));
    }
}
