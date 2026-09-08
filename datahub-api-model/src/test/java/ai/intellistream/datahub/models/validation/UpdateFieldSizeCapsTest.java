// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.validation.FieldValidationError;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update path enforces the same ceilings as create.
 *
 * <p>Create validates through annotations, update through these hand-written validators, and the two
 * had drifted: neither description nor metadata was bounded here, so update was a way to put into an
 * entity exactly what create had started refusing.
 *
 * <p>Driven through JSON like {@link RequiredFieldSetNullTest}, because which keys the caller did and
 * did not send is what these validators branch on.
 */
class UpdateFieldSizeCapsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static boolean mentions(List<FieldValidationError> errors, String messageKey) {
        return errors.stream().anyMatch(it -> List.of(it.getCodes()).contains(messageKey));
    }

    private static String repeat(int length) {
        return "x".repeat(length);
    }

    /** {@code {"k0":"v", "k1":"v", …}} with {@code count} entries. */
    private static String metadataJson(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "\"key_%d\":\"value\"".formatted(i))
                .collect(Collectors.joining(",", "{", "}"));
    }

    // ---- events ------------------------------------------------------------------------------

    @Test
    void event_descriptionOverMax_isRejected() {
        EventFields fields = mapper.readValue(
                "{\"description\": {\"set\": \"%s\"}}".formatted(repeat(FieldLimits.DESCRIPTION_MAX + 1)),
                EventFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.description.max.length.error"));
    }

    @Test
    void event_descriptionAtMax_isAccepted() {
        EventFields fields = mapper.readValue(
                "{\"description\": {\"set\": \"%s\"}}".formatted(repeat(FieldLimits.DESCRIPTION_MAX)),
                EventFields.class);
        assertTrue(fields.validateFields());
    }

    @Test
    void event_tooManyMetadataEntriesOnSet_isRejected() {
        EventFields fields = mapper.readValue(
                "{\"metadata\": {\"set\": %s}}".formatted(metadataJson(FieldLimits.METADATA_MAX_ENTRIES + 1)),
                EventFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.metadata.too.many.entries"));
    }

    @Test
    void event_tooManyMetadataEntriesOnAdd_isRejected() {
        // add grows the map, so it has to be bounded too — otherwise the cap is one request away.
        EventFields fields = mapper.readValue(
                "{\"metadata\": {\"add\": %s}}".formatted(metadataJson(FieldLimits.METADATA_MAX_ENTRIES + 1)),
                EventFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.metadata.too.many.entries"));
    }

    @Test
    void event_metadataValueOverMax_isRejected() {
        EventFields fields = mapper.readValue(
                "{\"metadata\": {\"set\": {\"k\": \"%s\"}}}".formatted(repeat(FieldLimits.METADATA_VALUE_MAX + 1)),
                EventFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.metadata.value.too.long"));
    }

    @Test
    void event_metadataKeyOverMax_isRejected() {
        EventFields fields = mapper.readValue(
                "{\"metadata\": {\"set\": {\"%s\": \"v\"}}}".formatted(repeat(FieldLimits.METADATA_KEY_MAX + 1)),
                EventFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.metadata.key.too.long"));
    }

    // ---- resources ---------------------------------------------------------------------------

    @Test
    void resource_descriptionOverMax_isRejected() {
        ResourceFields fields = mapper.readValue(
                "{\"description\": {\"set\": \"%s\"}}".formatted(repeat(FieldLimits.DESCRIPTION_MAX + 1)),
                ResourceFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "resource.description.max.length.error"));
    }

    @Test
    void resource_metadataValueOverMax_isRejected() {
        ResourceFields fields = mapper.readValue(
                "{\"metadata\": {\"set\": {\"k\": \"%s\"}}}".formatted(repeat(FieldLimits.METADATA_VALUE_MAX + 1)),
                ResourceFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "resource.metadata.value.too.long"));
    }

    @Test
    void resource_tooManyLabels_isRejected() {
        String labels = IntStream.range(0, FieldLimits.LABELS_MAX + 1)
                .mapToObj(i -> "\"label_%d\"".formatted(i))
                .collect(Collectors.joining(",", "[", "]"));
        ResourceFields fields = mapper.readValue(
                "{\"labels\": {\"set\": %s}}".formatted(labels), ResourceFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "resource.too.many.labels"));
    }

    @Test
    void resource_labelOverMaxLength_isRejected() {
        ResourceFields fields = mapper.readValue(
                "{\"labels\": {\"add\": [\"%s\"]}}".formatted(repeat(FieldLimits.LABEL_LENGTH_MAX + 1)),
                ResourceFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "resource.label.max.length.error"));
    }

    @Test
    void resource_ordinaryUpdate_isStillAccepted() {
        ResourceFields fields = mapper.readValue("""
                {"description": {"set": "a normal description"},
                 "metadata": {"add": {"work_order": "wo-sap-12344"}},
                 "labels": {"add": ["PIPE"]}}""", ResourceFields.class);
        assertTrue(fields.validateFields());
    }
}
