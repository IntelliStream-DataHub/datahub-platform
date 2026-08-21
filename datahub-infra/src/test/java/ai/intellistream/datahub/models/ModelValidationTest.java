// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.timeseries.Timeseries;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the create payloads reject a blank externalId/name. EventModel is validated by
 * EventService; DataSetModel by the resource path it transforms into. Previously these used
 * @NotNull + @Size(min=3), which let a 3+ char whitespace value ("   ") through.
 */
class ModelValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean violates(Object o, String field) {
        return validator.validate(o).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    void eventModel_blankExternalId_isViolation() {
        EventModel e = new EventModel();
        e.setExternalId("   ");
        assertTrue(violates(e, "externalId"));
    }

    @Test
    void dataSetModel_blankExternalIdAndName_areViolations() {
        DataSetModel ds = new DataSetModel();
        ds.setExternalId("");
        ds.setName("   ");
        assertTrue(violates(ds, "externalId"));
        assertTrue(violates(ds, "name"));
    }

    @Test
    void resource_blankExternalId_isViolation() {
        // The constraint ResourceService.create relies on to reject blank resources reaching it
        // from the resource_create / dataset_create MCP tools.
        Resource r = new Resource();
        r.setExternalId("   ");
        assertTrue(violates(r, "externalId"));
    }

    @Test
    void timeseries_unknownValueType_isViolation() {
        Timeseries ts = new Timeseries();
        ts.setValueType("flot"); // typo
        assertTrue(violates(ts, "valueType"));
    }

    @Test
    void timeseries_knownValueType_noValueTypeViolation() {
        Timeseries ts = new Timeseries();
        ts.setValueType("FLOAT"); // normalised to "float"
        assertFalse(violates(ts, "valueType"));
    }
}
