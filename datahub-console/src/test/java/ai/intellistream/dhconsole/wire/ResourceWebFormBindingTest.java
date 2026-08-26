// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.wire;

import ai.intellistream.datahub.resource.ResourceWebForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the JSON the console's own JavaScript posts still binds after {@code ResourceWebForm}
 * was re-parented from the retired {@code ResourceForm}/{@code NodeForm} hierarchy onto
 * {@code Resource}.
 *
 * <p>The bodies below are copied from what the browser actually sends:
 * {@code right-form-content/resources/form.js} builds one from the form fields (labels as plain
 * strings, a metadata object, optional relationTypes), and {@code tutorials/datasets.js} posts a
 * hand-written one. Both reach {@code ResourceApiController.save}/{@code update} as
 * {@code @RequestBody ResourceWebForm}, so a field that stopped binding here is a console feature
 * that silently stops working in the browser.
 */
class ResourceWebFormBindingTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("the resource form's payload binds field for field")
    void theResourceFormPayloadBinds() {
        ResourceWebForm form = mapper.readValue("""
                {"externalId":"pump_1","name":"Pump 1","description":"a pump","source":"SAP",
                 "isRoot":false,"dataSetId":"12","labels":["PIPE","PUMP"],
                 "metadata":{"work_order":"wo-1"},"relationTypes":["BELONGS_TO"],"relationFrom":"7"}
                """, ResourceWebForm.class);

        assertEquals("pump_1", form.getExternalId());
        assertEquals("Pump 1", form.getName());
        assertEquals("a pump", form.getDescription());
        assertEquals("SAP", form.getSource());
        assertEquals(Boolean.FALSE, form.getIsRoot());
        assertEquals(12L, form.getDataSetId());
        assertEquals(List.of("PIPE", "PUMP"), form.getLabels());
        assertEquals(Map.of("work_order", "wo-1"), form.getMetadata());
        // The console-only fields the "create with relations" UI sends.
        assertEquals(List.of("BELONGS_TO"), form.getRelationTypes());
        assertEquals(7L, form.getRelationFrom());
    }

    /** The tutorial seeds a resource with this exact body; isRoot=true is legal on a resource. */
    @Test
    void theTutorialSeedPayloadBinds() {
        ResourceWebForm form = mapper.readValue("""
                {"name":"Tutorial resource","externalId":"tutorial_resource_1",
                 "description":"","source":"","isRoot":true,"labels":["PIPE"],"metadata":{}}
                """, ResourceWebForm.class);

        assertEquals("Tutorial resource", form.getName());
        assertEquals(Boolean.TRUE, form.getIsRoot());
        assertTrue(form.getMetadata().isEmpty());
        assertNull(form.getRelationFrom());
    }

    /**
     * The console-only fields must not travel onward. The console copies the form into a plain
     * node shape before calling the api, and the api reads bodies strictly — but these being
     * write-only means even a direct serialization cannot leak a field the api has no place for.
     */
    @Test
    @DisplayName("relationFrom / relationTypes never serialize outward")
    void consoleOnlyFieldsAreWriteOnly() {
        ResourceWebForm form = new ResourceWebForm();
        form.setExternalId("pump_1");
        form.setName("Pump 1");
        form.setRelationFrom(7L);
        form.setRelationTypes(List.of("BELONGS_TO"));

        String json = mapper.writeValueAsString(form);

        assertFalse(json.contains("relationFrom"), json);
        assertFalse(json.contains("relationTypes"), json);
        assertTrue(json.contains("pump_1"), json);
    }
}
