// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event external-id contract, which is the most confusable thing in this change.
 *
 * <p>Same field name, different meaning. A resource or data set external id is the unique identity
 * of one thing. An <em>event</em> external id is a non-unique correlation key: the source system's
 * key for the subject the event is about. A purchase order created and then updated produces one
 * event per snapshot, all sharing the order's external id, and the order's history is "every event
 * with this external id, in time order". That is what makes the log an audit trail.
 *
 * <p>So events see the charset floor and nothing else. The naming policy does not apply to them at
 * all: it would be imposing a DataHub convention on data the operator does not own, and its rules
 * are meaningless there anyway — events deliberately share external ids, so uniqueness does not
 * apply and a near duplicate is the normal case rather than an anomaly.
 */
class EventExternalIdContractTest {

    @Test
    void anIndustrialTagIsAcceptedVerbatim() {
        EventFields fields = fieldsWithExternalId("21-PT-1234");

        assertThat(fields.validateFields()).isTrue();
        // Not rewritten. This used to become "21_PT_1234", so a caller correlating against the
        // source system's key was matching on a string that system had never issued.
        assertThat(fields.getExternalId().getSet()).isEqualTo("21-PT-1234");
    }

    @Test
    void theCharsetFloorStillApplies() {
        // The floor is the one rule events are subject to, and it is not relaxed for them.
        assertThat(fieldsWithExternalId("Pump-A 01").validateFields()).isFalse();   // space
        assertThat(fieldsWithExternalId("a/b").validateFields()).isFalse();          // slash
        assertThat(fieldsWithExternalId("pump\u0007a").validateFields()).isFalse();  // control char
    }

    @Test
    void lengthBoundsAreUnchanged() {
        assertThat(fieldsWithExternalId("ab").validateFields()).isFalse();
        assertThat(fieldsWithExternalId("abc").validateFields()).isTrue();
        assertThat(fieldsWithExternalId("a".repeat(256)).validateFields()).isTrue();
        assertThat(fieldsWithExternalId("a".repeat(257)).validateFields()).isFalse();
    }

    @Test
    void manyEventsMayShareOneExternalId() {
        // Nothing in validation makes an event external id unique, and nothing may: forcing
        // uniqueness would push integrations into synthetic per-snapshot ids or, worse, into
        // updating events in place, which destroys the append-only history.
        String orderKey = "PO-4500171";
        for (int snapshot = 0; snapshot < 5; snapshot++) {
            assertThat(fieldsWithExternalId(orderKey).validateFields()).isTrue();
        }
    }

    @Test
    void existingSnakeCaseEventIdsAreUnaffected() {
        EventFields fields = fieldsWithExternalId("work_order_sap_chemicals");
        assertThat(fields.validateFields()).isTrue();
        assertThat(fields.getExternalId().getSet()).isEqualTo("work_order_sap_chemicals");
    }

    private static EventFields fieldsWithExternalId(String externalId) {
        EventFields fields = new EventFields();
        fields.setExternalId(new UpdateStringField().set(externalId));
        return fields;
    }
}
