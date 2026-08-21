// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.models.IdCollection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the request shape of {@link TimeseriesFilter}, order-independent.
 *
 * <p>This was the thinnest filter of the family on one of the richest entities. It now inherits the
 * shared criteria from {@link NodeFilter}, which is where {@code externalIdPrefix}, {@code source},
 * {@code labels}, {@code createdTime} and {@code lastUpdatedTime} come from — it had none of them.
 * What is left below is what only a timeseries has.
 */
class TimeseriesFilterWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void timeseriesFilterWireShapeIsStable() {
        TimeseriesFilter f = new TimeseriesFilter();
        f.setId(List.of(12L, 18L));
        f.setExternalId(List.of("rpm_pump_1", "rpm_*"));
        f.setName(List.of("RPM%"));
        f.setSource(List.of("sap", "opc_*"));
        f.setLabels(List.of("PUMP"));
        f.setDataSetId(List.of(IdCollection.createFromId(21L)));
        f.setUnit(List.of("kg/hr", "deg_*"));
        f.setUnitExternalId(List.of("mass_flow_rate_kghr"));
        f.setValueType(List.of("FLOAT"));
        f.setMetadata(Map.of("vendor", "acme"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(f), Map.class);

        assertEquals(List.of("12", "18"), m.get("id")); // ids are strings on the wire
        assertEquals(List.of("rpm_pump_1", "rpm_*"), m.get("externalId"));
        assertEquals(List.of("RPM%"), m.get("name"));
        assertEquals(List.of("sap", "opc_*"), m.get("source"));
        assertEquals(List.of("PUMP"), m.get("labels"));
        assertEquals(List.of(Map.of("id", "21")), m.get("dataSetId"));
        assertEquals(List.of("kg/hr", "deg_*"), m.get("unit"));
        assertEquals(List.of("mass_flow_rate_kghr"), m.get("unitExternalId"));
        assertEquals(List.of("FLOAT"), m.get("valueType"));
        assertEquals(Map.of("vendor", "acme"), m.get("metadata"));

        assertFalse(m.containsKey("metadataKey"),
                "the metadataKey/metadataValue pair existed only because metadata could not express "
                        + "key-only matching; a null value in the map says it now");
        assertFalse(m.containsKey("metadataValue"), "see metadataKey");
        // unit, unitExternalId, valueType, source and dataSetId are asserted above as arrays. They
        // were each a single exact-match value once, and the names have come back to the singular
        // now that every one of them also accepts a bare value inbound. The type is what separates
        // today's field from the scalar it replaced, so it is the type the parity test pins.
        assertFalse(m.containsKey("externalIdPrefix"),
                "externalIdPrefix folded into externalId — send \"rpm_*\" instead");
        assertFalse(m.containsKey("minCreatedTime"),
                "DataFilter's min/max timestamps were never read by any query — they must not return");

        // Derived, not part of the request contract — they must not leak onto the wire.
        assertFalse(m.containsKey("externalIdHashes"),
                "getExternalIdHashes() is a derivation helper and is @JsonIgnore'd");
        assertFalse(m.containsKey("labelHashes"),
                "getLabelHashes() is a derivation helper and is @JsonIgnore'd");
        assertFalse(m.containsKey("externalIdPatterns"), "derived helper, @JsonIgnore'd");
        assertFalse(m.containsKey("namePatterns"), "derived helper, @JsonIgnore'd");
        assertFalse(m.containsKey("sourcePatterns"), "derived helper, @JsonIgnore'd");
    }

    @Test
    void externalIdHashesAreDerivedCaseInsensitively() {
        TimeseriesFilter lower = new TimeseriesFilter();
        lower.setExternalId(List.of("rpm_pump_1"));
        TimeseriesFilter upper = new TimeseriesFilter();
        upper.setExternalId(List.of("RPM_PUMP_1"));

        // External ids hash from the lowercased form everywhere in the platform, so a filter must
        // match a row however the caller cased it.
        assertEquals(lower.getExternalIdHashes(), upper.getExternalIdHashes());
    }

    @Test
    void labelHashesAreDerivedFromTheCanonicalName() {
        TimeseriesFilter loose = new TimeseriesFilter();
        loose.setLabels(List.of("pump a"));
        TimeseriesFilter canonical = new TimeseriesFilter();
        canonical.setLabels(List.of("PUMP_A"));

        // Label names are stored canonicalised, so filtering on how a human wrote it must find the
        // label as stored.
        assertEquals(canonical.getLabelHashes(), loose.getLabelHashes());
    }

    @Test
    void nullExternalIdsMeanNoRestrictionRatherThanMatchNothing() {
        TimeseriesFilter f = new TimeseriesFilter();
        assertTrue(f.getExternalIdHashes() == null,
                "null must stay null: an empty list would be read as 'match nothing'");
        assertTrue(f.getLabelHashes() == null, "same for labels");
    }
}
