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
     * geoLocation is not part of this DTO's REST contract in either direction — it exists for the
     * Pulsar payload, like {@code valueType}. An ASSET-labelled body is typed as an {@code Asset},
     * which carries the real field; a body with no type-label is a plain resource, which has
     * nowhere to store a location, so the field is unknown there and the api answers 400 rather
     * than accepting it and dropping it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void geoLocationIsNeitherReadNorWrittenOnAResource() {
        Resource r = new Resource();
        r.setExternalId("sensor_a");
        r.setName("Sensor A");
        r.setGeoLocation(new GeoLocation("{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}"));

        // Set in memory for the Pulsar path, absent from the JSON.
        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(r), Map.class);
        assertFalse(m.containsKey("geoLocation"));

        // And not bound back off the wire either.
        Resource in = mapper.readValue(
                "{\"externalId\":\"sensor_a\",\"name\":\"Sensor A\"," +
                "\"geoLocation\":{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}}",
                Resource.class);
        assertNull(in.getGeoLocation());
    }

}
