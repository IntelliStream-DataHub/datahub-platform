// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.models.forms.DataSetFields;
import ai.intellistream.datahub.timeseries.TimeseriesFields;
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

    // ---- data sets ---------------------------------------------------------------------------

    /**
     * These were the families the earlier fix did not reach. {@code DataSetFields} and
     * {@code TimeseriesFields} called {@code SizeRules} nowhere at all, so update was a way to store
     * a description, a metadata map and a label list that create refuses — and this test stopped at
     * events and resources, which are exactly the two it did reach.
     */
    @Test
    void dataset_descriptionOverMax_isRejected() {
        DataSetFields fields = mapper.readValue(
                "{\"description\": {\"set\": \"%s\"}}".formatted(repeat(FieldLimits.DESCRIPTION_MAX + 1)),
                DataSetFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "dataset.description.max.length.error"));
    }

    @Test
    void dataset_descriptionAtMax_isAccepted() {
        DataSetFields fields = mapper.readValue(
                "{\"description\": {\"set\": \"%s\"}}".formatted(repeat(FieldLimits.DESCRIPTION_MAX)),
                DataSetFields.class);
        assertTrue(fields.validateUpdateFields());
    }

    @Test
    void dataset_tooManyMetadataEntries_isRejected() {
        DataSetFields fields = mapper.readValue(
                "{\"metadata\": {\"set\": %s}}".formatted(metadataJson(FieldLimits.METADATA_MAX_ENTRIES + 1)),
                DataSetFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "dataset.metadata.too.many.entries"));
    }

    @Test
    void dataset_tooManyLabels_isRejected() {
        String labels = java.util.stream.IntStream.range(0, FieldLimits.LABELS_MAX + 1)
                .mapToObj(i -> "\"label_%d\"".formatted(i))
                .collect(Collectors.joining(",", "[", "]"));
        DataSetFields fields = mapper.readValue(
                "{\"labels\": {\"set\": %s}}".formatted(labels), DataSetFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "dataset.too.many.labels"));
    }

    // ---- time series -------------------------------------------------------------------------

    @Test
    void timeseries_descriptionOverMax_isRejected() {
        TimeseriesFields fields = mapper.readValue(
                "{\"description\": {\"set\": \"%s\"}}".formatted(repeat(FieldLimits.DESCRIPTION_MAX + 1)),
                TimeseriesFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "timeseries.description.max.length.error"));
    }

    @Test
    void timeseries_metadataValueOverMax_isRejected() {
        TimeseriesFields fields = mapper.readValue(
                "{\"metadata\": {\"set\": {\"k\": \"%s\"}}}".formatted(repeat(FieldLimits.METADATA_VALUE_MAX + 1)),
                TimeseriesFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "timeseries.metadata.value.too.long"));
    }

    @Test
    void timeseries_ordinaryUpdate_isStillAccepted() {
        TimeseriesFields fields = mapper.readValue(
                "{\"description\": {\"set\": \"a normal description\"}}", TimeseriesFields.class);
        assertTrue(fields.validateUpdateFields());
    }

    // ---- source: the cap update enforced was half the one create allows -------------------------

    /**
     * {@code NodeModel.source} is {@code ^$|.{2,128}} and {@code EventModel.source} is
     * {@code @Size(min = 2, max = 128)}, but both update validators rejected anything over 64. A
     * source of 65 to 128 characters could be created and then never updated: the entity was stuck
     * with a value its own update path refused to take back.
     */
    @Test
    void source_betweenTheOldCapAndTheCreateCap_isNowAccepted() {
        ResourceFields resource = mapper.readValue(
                "{\"source\": {\"set\": \"%s\"}}".formatted(repeat(100)), ResourceFields.class);
        assertTrue(resource.validateFields(), "create allows 128, so update must too");

        EventFields event = mapper.readValue(
                "{\"source\": {\"set\": \"%s\"}}".formatted(repeat(100)), EventFields.class);
        assertTrue(event.validateFields());
    }

    @Test
    void source_overTheCreateCap_isStillRejected() {
        ResourceFields resource = mapper.readValue(
                "{\"source\": {\"set\": \"%s\"}}".formatted(repeat(FieldLimits.SOURCE_MAX + 1)),
                ResourceFields.class);
        assertFalse(resource.validateFields());
        assertTrue(mentions(resource.getErrors(), "resource.source.max.length.error"));
    }
}
