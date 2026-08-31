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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
     * A plain resource has no location at all, in memory or on the wire.
     *
     * <p>The field used to exist on {@code Resource} and be {@code @JsonIgnore}d: it was the
     * Avro-reflected Pulsar payload, and the Neo4j consumer read a created asset's location off
     * it. The graph is written from the entities now, so the field went with the payload and
     * {@code geoLocation} lives on {@code Asset} alone — the one node type whose entity has the
     * column. This pins that it does not come back: a location on a plain resource has nowhere
     * to be stored, so the strict request reader answers 400 rather than dropping it silently.
     */
    @Test
    void aPlainResourceHasNoGeoLocation() {
        assertFalse(hasProperty(Resource.class, "geoLocation"),
                "geoLocation belongs to Asset; a plain resource has no column for it");
        assertTrue(hasProperty(Asset.class, "geoLocation"),
                "Asset is the node type that carries a location");
    }

    /** Likewise valueType, which is a time series' own concern. */
    @Test
    void aPlainResourceHasNoValueType() {
        assertFalse(hasProperty(Resource.class, "valueType"),
                "valueType belongs to Timeseries; it was on Resource only for the Pulsar payload");
        assertTrue(hasProperty(ai.intellistream.datahub.timeseries.Timeseries.class, "valueType"),
                "Timeseries is where a value type is a real field");
    }

    /** Declared fields including inherited ones, which is what a DTO's shape actually is. */
    private static boolean hasProperty(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return true;
            }
        }
        return false;
    }

}
