// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.forms;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distinction this form exists to make: "the caller did not mention this field" has to be
 * tellable from "the caller set it to false". The old whole-object form could not express it —
 * {@code Policy.isDeactivated} was a primitive {@code boolean}, so an omitted property arrived as
 * {@code false} and renaming a deactivated policy silently switched enforcement back on.
 */
class UpdatePolicyFormTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void omittedDeactivated_readsAsNoChange() {
        UpdatePolicyForm form = mapper.readValue(
                "{\"id\": 5, \"update\": {\"name\": {\"set\": \"sap-write-guard\"}}}",
                UpdatePolicyForm.class);

        // The whole point: absent is null, not false, so the service leaves the column alone.
        assertNull(form.getUpdate().getDeactivated().getSet());
        assertEquals("sap-write-guard", form.getUpdate().getName().getSet());
    }

    @Test
    void explicitFalse_readsAsActivate() {
        UpdatePolicyForm form = mapper.readValue(
                "{\"id\": 5, \"update\": {\"deactivated\": {\"set\": false}}}",
                UpdatePolicyForm.class);

        assertEquals(Boolean.FALSE, form.getUpdate().getDeactivated().getSet());
    }

    @Test
    void explicitTrue_readsAsDeactivate() {
        UpdatePolicyForm form = mapper.readValue(
                "{\"id\": 5, \"update\": {\"deactivated\": {\"set\": true}}}",
                UpdatePolicyForm.class);

        assertEquals(Boolean.TRUE, form.getUpdate().getDeactivated().getSet());
    }

    @Test
    void absentUpdateBlock_isAnEmptyPatchRatherThanNull() {
        // An item naming a policy but no changes must not dereference null in the service.
        UpdatePolicyForm form = mapper.readValue("{\"id\": 5}", UpdatePolicyForm.class);

        assertNotNull(form.getUpdate());
        assertNull(form.getUpdate().getName().getSet());
        assertNull(form.getUpdate().getDeactivated().getSet());
    }

    @Test
    void metadataSupportsSetAddAndRemove() {
        // add/remove is what makes shrinking a policy's metadata possible; the previous form
        // merged with putAll and could never drop a key.
        UpdatePolicyForm form = mapper.readValue("""
                {"id": 5, "update": {"metadata": {
                    "add": {"kind": "naming"},
                    "remove": ["stale"]
                }}}""", UpdatePolicyForm.class);

        assertEquals("naming", form.getUpdate().getMetadata().getAdd().get("kind"));
        assertTrue(form.getUpdate().getMetadata().getRemove().contains("stale"));
        assertNull(form.getUpdate().getMetadata().getSet());
    }

    @Test
    void identifiesByExternalId_separatelyFromRenamingIt() {
        // The entry's externalId picks the policy; update.externalId is the new value. A rename
        // therefore carries both, and they must stay distinguishable.
        UpdatePolicyForm form = mapper.readValue("""
                {"externalId": "policy_is_write_protected",
                 "update": {"externalId": {"set": "policy_write_guard"}}}""",
                UpdatePolicyForm.class);

        assertNull(form.getId());
        assertEquals("policy_is_write_protected", form.getExternalId());
        assertEquals("policy_write_guard", form.getUpdate().getExternalId().getSet());
    }

    @Test
    void clearableFieldsExposeSetNull() {
        UpdatePolicyForm form = mapper.readValue(
                "{\"id\": 5, \"update\": {\"description\": {\"setNull\": true}}}",
                UpdatePolicyForm.class);

        assertTrue(form.getUpdate().getDescription().getSetNull());
        assertFalse(form.getUpdate().getSource().getSetNull());
    }
}
