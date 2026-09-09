// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.models.UpdateEventForm;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event external-id contract, which is the most confusable thing in this area.
 *
 * <p>Same field name, different meaning. A resource or data set external id is the unique identity
 * of one thing. An <em>event</em> external id is a non-unique correlation key: the source system's
 * key for the subject the event is about. A purchase order created and then updated produces one
 * event per snapshot, all sharing the order's external id, and the order's history is "every event
 * with this external id, in time order". That is what makes the log an audit trail.
 *
 * <p>So events see the charset floor and nothing else. The naming policy does not apply to them at
 * all: it would be imposing a DataHub convention on data the operator does not own, and its rules
 * are meaningless there anyway, since events deliberately share external ids, so uniqueness does
 * not apply and a near duplicate is the normal case rather than an anomaly.
 *
 * <p><b>Where this is asserted moved, and so did the test.</b> It used to drive
 * {@link EventFields}, the update form, which carried an {@code externalId} of its own and ran
 * {@link ExternalIdRules} over it. An event's external id is now immutable, so that field is gone
 * and the floor is a bean constraint on {@link UpdateEventForm#getExternalId()}: the
 * <em>identifying</em> id on an update rather than a new value for one. The test also sat in
 * {@code datahub-commons} while every class it names lives in {@code datahub-api-model}, which is
 * why a module-scoped build did not notice it had stopped compiling.
 */
class EventExternalIdContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** Violations on the external id alone, so an unrelated required field cannot mask the result. */
    private int violations(String externalId) {
        return validator.validateProperty(new UpdateEventForm().setExternalId(externalId), "externalId").size();
    }

    @Test
    void anIndustrialTagIsAcceptedVerbatim() {
        UpdateEventForm form = new UpdateEventForm().setExternalId("21-PT-1234");

        assertEquals(0, violations("21-PT-1234"));
        // Not rewritten. This used to become "21_PT_1234", so a caller correlating against the
        // source system's key was matching on a string that system had never issued.
        assertEquals("21-PT-1234", form.getExternalId());
    }

    @Test
    void theCharsetFloorStillApplies() {
        // The floor is the one rule events are subject to, and it is not relaxed for them.
        assertTrue(violations("Pump-A 01") > 0, "space");
        assertTrue(violations("a/b") > 0, "slash");
        assertTrue(violations("pump" + (char) 7 + "a") > 0, "control character");
        assertTrue(violations("pump$a") > 0, "outside the charset");
    }

    @Test
    void lengthBoundsAreUnchanged() {
        assertTrue(violations("ab") > 0);
        assertEquals(0, violations("abc"));
        assertEquals(0, violations("a".repeat(256)));
        assertTrue(violations("a".repeat(257)) > 0);
    }

    @Test
    void manyEventsMayShareOneExternalId() {
        // Nothing in validation makes an event external id unique, and nothing may: forcing
        // uniqueness would push integrations into synthetic per-snapshot ids or, worse, into
        // updating events in place, which destroys the append-only history.
        String orderKey = "PO-4500171";
        for (int snapshot = 0; snapshot < 5; snapshot++) {
            assertEquals(0, violations(orderKey), "snapshot " + snapshot);
        }
    }

    @Test
    void existingSnakeCaseEventIdsAreUnaffected() {
        UpdateEventForm form = new UpdateEventForm().setExternalId("work_order_sap_chemicals");

        assertEquals(0, violations("work_order_sap_chemicals"));
        assertEquals("work_order_sap_chemicals", form.getExternalId());
    }

    /**
     * The immutability that removed the field this test used to drive. Asserted structurally
     * because it is the whole point: a patchable external id would move a KVRocks key that every
     * sibling event shares, so the update form must not offer one.
     */
    @Test
    void anEventExternalIdIsNotPatchable() {
        assertTrue(Arrays.stream(EventFields.class.getDeclaredFields())
                        .noneMatch(field -> "externalId".equals(field.getName())),
                "EventFields must not regain an externalId: it is an event's identity, not a "
                        + "property, and renaming one would silently take every sibling event along");
    }
}
