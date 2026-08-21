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
 * Pins the on-the-wire shape of {@link EventModel}, in particular that related resources travel as
 * a single {@code relatedResources} list of id/externalId objects.
 *
 * <p>The old {@code relatedResourceIds} / {@code relatedResourceExternalIds} pair is gone for good:
 * two parallel lists could be set independently and so could drift apart. The exact-key-set
 * assertion below is what stops either name being reintroduced by accident.
 */
class EventModelWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private EventModel event() {
        EventModel e = new EventModel();
        e.setId("db02f65e-3b77-4d5e-a8f6-4a2b5d2c8f19");
        e.setExternalId("alarm_pipe_overpressure");
        e.setType("alarm");
        e.setSubType("overpressure");
        e.setStatus("OPEN");
        e.setDescription("Pipe A1212 briefly exceeded 40 bar");
        e.setDataSetId(12L);
        e.setSource("SAP");
        e.setEventTime(ZonedDateTime.parse("2024-06-17T12:34:56Z"));
        e.setCreatedTime(ZonedDateTime.parse("2024-06-17T12:34:56Z"));
        e.setLastUpdatedTime(ZonedDateTime.parse("2024-06-18T00:00:00Z"));
        e.setMetadata(Map.of("severity", "high"));
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void relatedResourcesSerializeAsOneListOfIdCollections() {
        EventModel e = event();
        IdCollection related = new IdCollection();
        related.setId(34L);
        related.setExternalId("sensor_abc");
        e.setRelatedResources(List.of(related));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(e), Map.class);

        List<Object> entries = (List<Object>) m.get("relatedResources");
        assertEquals(1, entries.size());
        Map<String, Object> entry = assertInstanceOf(Map.class, entries.getFirst());
        // IdCollection.id goes through ToStringSerializer, so the id is a JSON string —
        // consistent with Resource.id and EventFilter, and coerced back to Long on input.
        assertEquals("34", entry.get("id"));
        assertEquals("sensor_abc", entry.get("externalId"));
        assertEquals(Set.of("id", "externalId"), entry.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventWireShapeIsStable() {
        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(event()), Map.class);

        assertEquals("alarm_pipe_overpressure", m.get("externalId"));
        assertEquals("alarm", m.get("type"));
        assertEquals("OPEN", m.get("status"));
        assertEquals("12", m.get("dataSetId"));   // ToStringSerializer
        assertEquals("2024-06-17T12:34:56Z", m.get("eventTime"));
        assertEquals(List.of(), m.get("relatedResources"));

        // The two parallel lists this model replaced must never come back.
        assertFalse(m.containsKey("relatedResourceIds"));
        assertFalse(m.containsKey("relatedResourceExternalIds"));
        // @JsonIgnore'd.
        assertFalse(m.containsKey("hasEventTime"));

        assertEquals(Set.of("id", "externalId", "type", "subType", "status", "description",
                        "dataSetId", "source", "eventTime", "createdTime", "lastUpdatedTime",
                        "metadata", "relatedResources"),
                m.keySet());
    }

    @Test
    void relatedResourcesRoundTripFromEitherSide() {
        // A caller may name a resource by id, by externalId, or both. All three must deserialize;
        // the API fills in whichever side was omitted.
        String json = """
                {
                  "externalId": "alarm_1",
                  "eventTime": "2024-06-17T12:34:56Z",
                  "relatedResources": [
                    { "id": "34" },
                    { "externalId": "sensor_abc" },
                    { "id": 7, "externalId": "pump_7" }
                  ]
                }
                """;

        EventModel e = mapper.readValue(json, EventModel.class);

        assertEquals(3, e.getRelatedResources().size());
        assertEquals(34L, e.getRelatedResources().get(0).getId());
        assertEquals(null, e.getRelatedResources().get(0).getExternalId());
        assertEquals(null, e.getRelatedResources().get(1).getId());
        assertEquals("sensor_abc", e.getRelatedResources().get(1).getExternalId());
        assertEquals(7L, e.getRelatedResources().get(2).getId());
        assertEquals("pump_7", e.getRelatedResources().get(2).getExternalId());
    }

    @Test
    void legacyRelatedResourceKeysAreIgnoredNotMapped() {
        // Documents the accepted cost of the hard cut: EventModel is @JsonIgnoreProperties
        // (ignoreUnknown = true), so a client still sending the old keys gets a 200 with the
        // relations dropped rather than an error. Callers must migrate to relatedResources.
        String json = """
                {
                  "externalId": "alarm_1",
                  "eventTime": "2024-06-17T12:34:56Z",
                  "relatedResourceIds": [34],
                  "relatedResourceExternalIds": ["sensor_abc"]
                }
                """;

        EventModel e = mapper.readValue(json, EventModel.class);

        assertEquals(List.of(), e.getRelatedResources());
    }
}
