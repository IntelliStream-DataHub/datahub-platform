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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pins the exact on-the-wire shape of {@link Asset} (field set + serialized values),
 * order-independent — the same net every node DTO carries. Asset is the one DTO whose
 * {@code geoLocation} serializes on reads.
 */
class AssetWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @SuppressWarnings("unchecked")
    void assetWireShapeIsStable() {
        Asset a = new Asset();
        a.setId(34L);
        a.setExternalId("plant_a");
        a.setName("Plant A");
        a.setIsRoot(true);
        a.setDescription("desc");
        a.setDataSetId(12L);
        a.setSource("src");
        a.setLabels(List.of("ASSET", "PLANT"));
        a.setMetadata(Map.of("k", "v"));
        a.setGeoLocation(new GeoLocation("{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}"));
        a.setCreatedTime(ZonedDateTime.parse("2024-06-17T12:34:56Z"));
        a.setLastUpdatedTime(ZonedDateTime.parse("2024-06-18T00:00:00Z"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(a), Map.class);

        assertEquals("34", m.get("id"));            // ToStringSerializer
        assertEquals("plant_a", m.get("externalId"));
        assertEquals("Plant A", m.get("name"));
        assertEquals(true, m.get("isRoot"));
        assertEquals("desc", m.get("description"));
        assertEquals("12", m.get("dataSetId"));     // ToStringSerializer
        assertEquals("src", m.get("source"));
        assertEquals(List.of("ASSET", "PLANT"), m.get("labels"));
        assertEquals(Map.of("k", "v"), m.get("metadata"));
        assertEquals(List.of(), m.get("relatedResources"));
        assertEquals("2024-06-17T12:34:56Z", m.get("createdTime"));
        assertEquals("2024-06-18T00:00:00Z", m.get("lastUpdatedTime"));

        // A nested GeoJSON object on the wire, never an escaped string.
        Map<String, Object> geo = (Map<String, Object>) m.get("geoLocation");
        assertEquals("Point", geo.get("type"));
        assertEquals(List.of(10.75, 59.91), geo.get("coordinates"));

        // Exact key set (order-independent) — nothing added or dropped.
        assertEquals(Set.of("id", "externalId", "name", "isRoot", "geoLocation", "relatedResources",
                "metadata", "description", "dataSetId", "source", "labels",
                "createdTime", "lastUpdatedTime"),
                m.keySet());
    }

    /** A polygon (general geometry, not just Point) survives the round-trip. */
    @Test
    @SuppressWarnings("unchecked")
    void geoLocationAcceptsGeneralGeometry() {
        Asset a = new Asset();
        a.setExternalId("area_a");
        a.setName("Area A");
        a.setGeoLocation(new GeoLocation(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"));

        String json = mapper.writeValueAsString(a);
        Map<String, Object> m = mapper.readValue(json, Map.class);
        Map<String, Object> geo = (Map<String, Object>) m.get("geoLocation");
        assertEquals("Polygon", geo.get("type"));
        assertInstanceOf(List.class, geo.get("coordinates"));

        Asset back = mapper.readValue(json, Asset.class);
        Map<String, Object> backGeo = mapper.readValue(back.getGeoLocation().getJson(), Map.class);
        assertEquals("Polygon", backGeo.get("type"));
    }
}
