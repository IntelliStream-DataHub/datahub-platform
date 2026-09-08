// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.validation.FieldValidationError;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The length ceilings on an event update's categorical fields. None of them were tested, and the
 * status branch had been reading {@code type}'s value into its error since it was written — an NPE
 * (so a 500, not a 400) for any request that set an over-long status without also setting a type.
 *
 * <p>Built from JSON like {@link RequiredFieldSetNullTest}, because the bug depended on which keys
 * the caller did <em>not</em> send.
 */
class EventFieldLengthRulesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final String OVERLONG_129 = "x".repeat(129);
    private static final String OVERLONG_65 = "x".repeat(65);

    private static boolean mentions(List<FieldValidationError> errors, String messageKey) {
        return errors.stream().anyMatch(it -> List.of(it.getCodes()).contains(messageKey));
    }

    private EventFields fields(String json) {
        return mapper.readValue(json, EventFields.class);
    }

    @Test
    void status_overLongWithoutAType_isRejectedNotAnNpe() {
        // The regression case: status set, type absent. The error used to be built from
        // type.getSet().length(), which is a null dereference exactly here.
        EventFields fields = fields("{\"status\": {\"set\": \"" + OVERLONG_129 + "\"}}");
        boolean valid = assertDoesNotThrow(fields::validateFields);
        assertFalse(valid);
        assertTrue(mentions(fields.getErrors(), "event.status.max.length.error"));
    }

    @Test
    void type_over128_isRejected() {
        EventFields fields = fields("{\"type\": {\"set\": \"" + OVERLONG_129 + "\"}}");
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.type.max.length.error"));
    }

    @Test
    void subType_over128_isRejected() {
        EventFields fields = fields("{\"subType\": {\"set\": \"" + OVERLONG_129 + "\"}}");
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.subType.max.length.error"));
    }

    @Test
    void source_over64_isRejected() {
        EventFields fields = fields("{\"source\": {\"set\": \"" + OVERLONG_65 + "\"}}");
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.source.max.length.error"));
    }

    @Test
    void valuesAtTheCeiling_areAccepted() {
        EventFields fields = fields(
                "{\"type\": {\"set\": \"" + "x".repeat(128) + "\"},"
                        + " \"subType\": {\"set\": \"" + "x".repeat(128) + "\"},"
                        + " \"status\": {\"set\": \"" + "x".repeat(128) + "\"},"
                        + " \"source\": {\"set\": \"" + "x".repeat(64) + "\"}}");
        assertTrue(fields.validateFields());
    }
}
