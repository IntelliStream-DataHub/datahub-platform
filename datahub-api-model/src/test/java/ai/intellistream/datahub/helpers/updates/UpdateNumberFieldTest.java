// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code setNull} on a numeric patch field means "clear this field to null". The bug this pins:
 * because {@code setNull} defaults to (non-null) {@code false} and the field object is always
 * instantiated, a {@code getSetNull() != null} guard was always true — so a resource update nulled
 * the dataset on every call. {@link UpdateNumberField#getSetNull()} returns a guarded primitive so
 * that {@code if (field.getSetNull())} is the correct, safe check.
 */
class UpdateNumberFieldTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void defaultInstance_doesNotRequestNull() {
        // A field the caller never mentioned must not read as "clear me".
        assertFalse(new UpdateNumberField().getSetNull());
    }

    @Test
    void absentSetNull_doesNotRequestNull() {
        UpdateNumberField field = mapper.readValue("{\"set\": 5}", UpdateNumberField.class);
        assertFalse(field.getSetNull());
    }

    @Test
    void explicitJsonNull_doesNotRequestNull_andDoesNotThrow() {
        UpdateNumberField field = mapper.readValue("{\"setNull\": null}", UpdateNumberField.class);
        assertFalse(field.getSetNull());
    }

    @Test
    void explicitTrue_requestsNull() {
        UpdateNumberField field = mapper.readValue("{\"setNull\": true}", UpdateNumberField.class);
        assertTrue(field.getSetNull());
    }
}
