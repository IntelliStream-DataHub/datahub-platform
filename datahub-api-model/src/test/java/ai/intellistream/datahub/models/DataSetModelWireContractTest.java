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

/**
 * Pins the exact on-the-wire shape of {@link DataSetModel} (field set + serialized values + the
 * canonicalization on externalId/policies), order-independent. Contract net for the {@code NodeModel}
 * re-parent: it must stay green.
 */
class DataSetModelWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @SuppressWarnings("unchecked")
    void dataSetModelWireShapeIsStable() {
        DataSetModel d = new DataSetModel();
        d.setId(12L);
        d.setExternalId("COM-99-PT-1034");  // stored verbatim — no longer canonicalized
        d.setName("Data Set");
        d.setDescription("desc");
        d.setPolicies(List.of("Policy.A"));  // canonicalized -> policy_a
        d.setMetadata(Map.of("k", "v"));
        d.setConnectedDataSets(List.of(5L));
        d.setLabels(List.of("DATASET", "CHEMICALS"));
        d.setSource("src");                  // hoisted onto NodeModel — every node type carries it
        d.setCreatedTime(ZonedDateTime.parse("2024-06-17T12:34:56Z"));
        d.setLastUpdatedTime(ZonedDateTime.parse("2024-06-18T00:00:00Z"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(d), Map.class);

        assertEquals("12", m.get("id"));                 // ToStringSerializer
        assertEquals("COM-99-PT-1034", m.get("externalId")); // verbatim: what was sent is what is read back
        assertEquals("Data Set", m.get("name"));
        assertEquals("desc", m.get("description"));
        assertEquals(List.of("policy_a"), m.get("policies")); // canonicalized
        assertEquals(Map.of("k", "v"), m.get("metadata"));
        // Strings, not numbers: these are xxHash node ids, and this was the last id-shaped list on
        // the wire a JavaScript client could lose precision on.
        assertEquals(List.of("5"), m.get("connectedDataSets"));
        assertEquals(List.of("DATASET", "CHEMICALS"), m.get("labels"));
        assertEquals("src", m.get("source"));
        assertEquals(List.of(), m.get("relatedResources"));
        assertEquals("2024-06-17T12:34:56Z", m.get("createdTime"));
        assertEquals("2024-06-18T00:00:00Z", m.get("lastUpdatedTime"));

        assertEquals(Set.of("id", "externalId", "name", "description", "policies", "metadata",
                "connectedDataSets", "labels", "relatedResources", "source", "createdTime", "lastUpdatedTime"), m.keySet());
    }
}
