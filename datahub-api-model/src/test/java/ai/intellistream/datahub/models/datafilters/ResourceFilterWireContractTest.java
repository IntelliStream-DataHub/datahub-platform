// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.models.IdCollection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the request shape of {@link ResourceFilter}, order-independent.
 *
 * <p>Most of the shape now comes from {@link NodeFilter}, so this doubles as the check that
 * inheriting the base did not change what goes on the wire. It also guards the fields that were
 * removed on the way in: the singular {@code id}/{@code externalId}/{@code name} that duplicated
 * the plural forms, a {@code cursor} that was documented but never read, and a
 * {@code lastUpdatedTimeHR} whose setter logged "not implemented" and discarded the value.
 */
class ResourceFilterWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void resourceFilterWireShapeIsStable() {
        ResourceFilter f = new ResourceFilter();
        f.setId(List.of(12L, 18L));
        f.setExternalId(List.of("pump_2", "spare_*"));
        f.setName(List.of("Valve%"));
        f.setSource(List.of("sap", "opc_*"));
        f.setLabels(List.of("PUMP"));
        f.setIsRoot(true);
        f.setDataSetId(List.of(IdCollection.createFromId(3L)));
        f.setMetadata(Map.of("k", "v"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(f), Map.class);

        assertEquals(List.of("12", "18"), m.get("id")); // ids are strings on the wire
        assertEquals(List.of("pump_2", "spare_*"), m.get("externalId"));
        assertEquals(List.of("Valve%"), m.get("name"));
        assertEquals(List.of("sap", "opc_*"), m.get("source"));
        assertEquals(List.of("PUMP"), m.get("labels"));
        assertEquals(true, m.get("isRoot"));
        assertEquals(Map.of("k", "v"), m.get("metadata"));
        assertEquals(List.of(Map.of("id", "3")), m.get("dataSetId"));

        // id, externalId, name and source are the four asserted above. They read as singular but
        // serialize as arrays, which is the whole point: each also accepts a bare value inbound, so
        // the singular name fits the common one-value call without the field ever stopping being a
        // list. What must not come back is a *second*, scalar field beside one of them — that pair
        // is what used to AND together and narrow the query in a way no caller intended.
        assertFalse(m.containsKey("cursor"),
                "the cursor field was documented but never read — it must not return");
        assertFalse(m.containsKey("lastUpdatedTimeHR"),
                "lastUpdatedTimeHR discarded whatever was sent to it — it must not return");
        assertFalse(m.containsKey("externalIdPrefix"),
                "externalIdPrefix folded into externalId — send \"sap_*\" instead");
        assertFalse(m.containsKey("externalIdHash"), "derived helper, @JsonIgnore'd");
        assertFalse(m.containsKey("externalIdHashes"), "derived helper, @JsonIgnore'd");
        assertFalse(m.containsKey("labelHashes"), "derived helper, @JsonIgnore'd");
        assertFalse(m.containsKey("externalIdPatterns"), "derived helper, @JsonIgnore'd");
        assertFalse(m.containsKey("namePatterns"), "derived helper, @JsonIgnore'd");
        assertFalse(m.containsKey("sourcePatterns"), "derived helper, @JsonIgnore'd");
    }
}
