// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.timeseries;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the exact on-the-wire shape of {@link Timeseries} (field set + serialized values + the per-field
 * canonicalization on externalId/unitExternalId/valueType), order-independent. Contract net for the
 * {@code NodeModel} hoist + fluent-builder normalization: re-parenting must leave this green.
 */
class TimeseriesWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @SuppressWarnings("unchecked")
    void timeseriesWireShapeIsStable() {
        Timeseries ts = new Timeseries();
        ts.setId(34L);
        ts.setExternalId("Engine.Temp");     // stored verbatim — no longer canonicalized
        ts.setName("Engine Temp");
        ts.setMetadata(Map.of("k", "v"));
        ts.setUnit("Deg C");
        ts.setUnitExternalId("Deg.C");       // canonicalized -> deg_c
        ts.setDescription("desc");
        ts.setDataSetId(21L);
        ts.setValueType("FLOAT");            // normalized -> float
        ts.setSource("src");                 // hoisted onto NodeModel
        ts.setCreatedTime(ZonedDateTime.parse("2024-06-17T12:34:56Z"));
        ts.setLastUpdatedTime(ZonedDateTime.parse("2024-06-18T00:00:00Z"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(ts), Map.class);

        assertEquals("34", m.get("id"));                 // ToStringSerializer
        assertEquals("Engine.Temp", m.get("externalId")); // verbatim: what was sent is what is read back
        assertEquals("Engine Temp", m.get("name"));
        assertEquals(Map.of("k", "v"), m.get("metadata"));
        assertEquals("Deg C", m.get("unit"));
        assertEquals("deg_c", m.get("unitExternalId"));  // canonicalized
        assertEquals("desc", m.get("description"));
        assertEquals("21", m.get("dataSetId"));          // ToStringSerializer
        assertEquals("float", m.get("valueType"));       // normalized
        assertEquals("src", m.get("source"));
        assertEquals("2024-06-17T12:34:56Z", m.get("createdTime"));
        assertEquals("2024-06-18T00:00:00Z", m.get("lastUpdatedTime"));
        assertEquals(List.of(), m.get("relatedResources"));
        // labels hoisted onto NodeModel; a timeseries self-types with the TIMESERIES type-label, which
        // is also the discriminator that routes it to the timeseries path on /resources/create.
        assertEquals(List.of("TIMESERIES"), m.get("labels"));

        // tableEngine is deliberately absent: which ClickHouse engine backs the series is an
        // internal storage decision a caller cannot act on, so it is @JsonIgnore'd. It is still a
        // field — the graph projection carries it and in-process readers use it — just not a
        // wire one.
        assertFalse(m.containsKey("tableEngine"), m.toString());

        assertEquals(Set.of("id", "externalId", "name", "metadata", "unit", "unitExternalId",
                "relatedResources", "description", "dataSetId", "source", "labels",
                "valueType", "createdTime", "lastUpdatedTime"), m.keySet());
    }
}
