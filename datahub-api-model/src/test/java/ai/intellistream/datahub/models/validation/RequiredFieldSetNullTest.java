// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.models.forms.DataSetFields;
import ai.intellistream.datahub.timeseries.TimeseriesFields;
import ai.intellistream.datahub.validation.FieldValidationError;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code setNull: true} against a field the object cannot exist without must fail loudly.
 *
 * <p>The bug these pin: it used to fall through every branch and return 200. The caller was told
 * the write succeeded, read the field back unchanged, and had no way to tell "not nullable" from
 * "your request was applied". The resource {@code name} case is the sharpest — a "Name cannot be
 * null" error already existed but was nested inside the {@code name.set != null} branch, so it
 * only ever fired for requests that were simultaneously setting a name.
 *
 * <p>Asserted through JSON rather than the fluent builders on purpose: the no-op depended on which
 * keys the caller did and did not send, so a test that constructs the fields directly would not
 * reproduce it.
 */
class RequiredFieldSetNullTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static boolean mentions(List<FieldValidationError> errors, String messageKey) {
        return errors.stream().anyMatch(it -> List.of(it.getCodes()).contains(messageKey));
    }

    // ---- Resources -------------------------------------------------------------------------

    @Test
    void resource_setNullOnName_isRejected() {
        ResourceFields fields = mapper.readValue("{\"name\": {\"setNull\": true}}", ResourceFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "resource.name.null.error"));
    }

    @Test
    void resource_setNullOnExternalId_isRejected() {
        ResourceFields fields = mapper.readValue("{\"externalId\": {\"setNull\": true}}", ResourceFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "resource.external.id.null.error"));
    }

    @Test
    void resource_setNullOnDataSetId_stillAllowed() {
        // The dataset really is optional on a resource — this one must keep working.
        ResourceFields fields = mapper.readValue("{\"dataSetId\": {\"setNull\": true}}", ResourceFields.class);
        assertTrue(fields.validateFields());
    }

    @Test
    void resource_renamingIsUntouched() {
        ResourceFields fields = mapper.readValue("{\"name\": {\"set\": \"Pump A\"}}", ResourceFields.class);
        assertTrue(fields.validateFields());
    }

    // ---- Timeseries ------------------------------------------------------------------------

    @Test
    void timeseries_setNullOnName_isRejected() {
        TimeseriesFields fields = mapper.readValue("{\"name\": {\"setNull\": true}}", TimeseriesFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "timeseries.name.null.error"));
    }

    @Test
    void timeseries_setNullOnExternalId_isRejected() {
        TimeseriesFields fields = mapper.readValue("{\"externalId\": {\"setNull\": true}}", TimeseriesFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "timeseries.external.id.null.error"));
    }

    // ---- Data sets -------------------------------------------------------------------------

    @Test
    void dataSet_setNullOnName_isRejected() {
        DataSetFields fields = mapper.readValue("{\"name\": {\"setNull\": true}}", DataSetFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "dataset.name.null.error"));
    }

    @Test
    void dataSet_setNullOnExternalId_isRejected() {
        DataSetFields fields = mapper.readValue("{\"externalId\": {\"setNull\": true}}", DataSetFields.class);
        assertFalse(fields.validateUpdateFields());
        assertTrue(mentions(fields.getErrors(), "dataset.external.id.null.error"));
    }

    @Test
    void dataSet_setNullOnDescription_stillAllowed() {
        DataSetFields fields = mapper.readValue("{\"description\": {\"setNull\": true}}", DataSetFields.class);
        assertTrue(fields.validateUpdateFields());
    }

    // ---- Events ----------------------------------------------------------------------------

    @Test
    void event_setNullOnType_isRejected() {
        // Previously honoured, and the most damaging of the set: events.type is non-nullable in
        // ClickHouse, so the null landed as an empty string and every later read of that event
        // failed against a client model that declares type required.
        EventFields fields = mapper.readValue("{\"type\": {\"setNull\": true}}", EventFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.type.null.error"));
    }

    @Test
    void event_setNullOnExternalId_isRejected() {
        EventFields fields = mapper.readValue("{\"externalId\": {\"setNull\": true}}", EventFields.class);
        assertFalse(fields.validateFields());
        assertTrue(mentions(fields.getErrors(), "event.external.id.null.error"));
    }

    /**
     * Event time is no longer clearable because it is no longer settable: it cannot be changed after
     * creation at all — the ClickHouse events table is partitioned by it, so the mutation is refused
     * outright. Absent from the form, so the request is rejected as an unknown field before any
     * setNull rule could apply.
     */
    @Test
    void event_eventTimeIsNotPartOfTheUpdateContract() {
        assertFalse(propertyNamesOf(EventFields.class).contains("eventTime"),
                "event time is immutable after creation, so it is not part of the update form");
    }

    private static java.util.List<String> propertyNamesOf(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(java.lang.reflect.Field::getName)
                .toList();
    }

    @Test
    void event_setNullOnDataSetId_isAllowed() {
        // 0 is the "no dataset" sentinel in ClickHouse, so detaching an event is expressible.
        EventFields fields = mapper.readValue("{\"dataSetId\": {\"setNull\": true}}", EventFields.class);
        assertTrue(fields.validateFields());
    }

    @Test
    void event_setNullOnOptionalFields_isAllowed() {
        EventFields fields = mapper.readValue(
                "{\"description\": {\"setNull\": true}, \"subType\": {\"setNull\": true},"
                        + " \"status\": {\"setNull\": true}, \"source\": {\"setNull\": true}}",
                EventFields.class);
        assertTrue(fields.validateFields());
    }
}
