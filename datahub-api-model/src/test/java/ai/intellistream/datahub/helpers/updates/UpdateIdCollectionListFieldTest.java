// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import ai.intellistream.datahub.models.IdCollection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The related-resource patch field. Unlike its predecessor — which advertised set/add/remove but
 * silently honoured only {@code set} — all three verbs are applied, so each must deserialize and
 * stay distinguishable from "not supplied".
 */
class UpdateIdCollectionListFieldTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void allThreeVerbsDeserialize() {
        String json = """
                {
                  "set":    [{ "externalId": "pump_7" }],
                  "add":    [{ "id": 34 }],
                  "remove": [{ "id": 12, "externalId": "valve_12" }]
                }
                """;

        UpdateIdCollectionListField field = mapper.readValue(json, UpdateIdCollectionListField.class);

        assertEquals(1, field.getSet().size());
        assertEquals("pump_7", List.copyOf(field.getSet()).getFirst().getExternalId());
        assertEquals(34L, List.copyOf(field.getAdd()).getFirst().getId());
        IdCollection removed = List.copyOf(field.getRemove()).getFirst();
        assertEquals(12L, removed.getId());
        assertEquals("valve_12", removed.getExternalId());
    }

    @Test
    void absentFieldLeavesEveryVerbNull() {
        // "untouched" must stay distinguishable from "set to empty": a null set leaves the stored
        // list alone, whereas an empty set clears it.
        UpdateIdCollectionListField field = mapper.readValue("{}", UpdateIdCollectionListField.class);

        assertNull(field.getSet());
        assertNull(field.getAdd());
        assertNull(field.getRemove());
    }

    @Test
    void emptySetIsNotNull() {
        UpdateIdCollectionListField field = mapper.readValue("{\"set\": []}", UpdateIdCollectionListField.class);

        assertEquals(0, field.getSet().size());
        assertNull(field.getAdd());
    }
}
