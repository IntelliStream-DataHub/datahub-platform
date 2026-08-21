// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.validation.EventFields;
import org.apache.avro.Schema;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventCudMessage} is published with {@code Schema.AVRO(...)}, so its Avro schema is derived
 * by reflection over the DTOs — meaning a field change on {@code EventModel} is a wire change.
 *
 * <p>Collapsing the two parallel related-resource lists into one {@code List<IdCollection>}
 * introduces a nested record where there were only primitive arrays. This pins that the reflected
 * schema is what we expect, so the assumption is a test rather than an assertion in a plan.
 */
class EventCudMessageAvroSchemaTest {

    private Schema eventCudSchema() {
        var definition = SchemaDefinition.<EventCudMessage>builder()
                .withPojo(EventCudMessage.class)
                .build();
        return org.apache.avro.reflect.ReflectData.AllowNull.get()
                .getSchema(definition.getPojo());
    }

    /** Unwraps the ["null", T] union that AllowNull reflection wraps every field in. */
    private static Schema unwrapNullable(Schema schema) {
        if (schema.getType() != Schema.Type.UNION) {
            return schema;
        }
        return schema.getTypes().stream()
                .filter(it -> it.getType() != Schema.Type.NULL)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void relatedResourcesReflectsAsAnArrayOfIdExternalIdRecords() {
        Schema events = unwrapNullable(eventCudSchema().getField("events").schema());
        assertEquals(Schema.Type.ARRAY, events.getType());

        Schema event = unwrapNullable(events.getElementType());
        Schema.Field relatedResources = event.getField("relatedResources");
        assertNotNull(relatedResources, "EventModel must carry a single relatedResources list");

        Schema list = unwrapNullable(relatedResources.schema());
        assertEquals(Schema.Type.ARRAY, list.getType());

        Schema entry = unwrapNullable(list.getElementType());
        assertEquals(Schema.Type.RECORD, entry.getType());
        // Field order is an artefact of reflection, so pin the set rather than the sequence.
        assertEquals(Set.of("id", "externalId"),
                entry.getFields().stream().map(Schema.Field::name).collect(Collectors.toSet()));
    }

    @Test
    void theTwoParallelListsAreGoneFromTheWireSchema() {
        Schema event = unwrapNullable(unwrapNullable(eventCudSchema().getField("events").schema()).getElementType());

        assertNull(event.getField("relatedResourceIds"));
        assertNull(event.getField("relatedResourceExternalIds"));
    }

    @Test
    void patchFormCarriesTheRelatedResourceUpdateVerbs() {
        Schema updates = unwrapNullable(eventCudSchema().getField("updateEvents").schema());
        Schema form = unwrapNullable(unwrapNullable(updates.getElementType()));
        Schema fields = unwrapNullable(form.getField("update").schema());

        Schema relatedResources = unwrapNullable(fields.getField("relatedResources").schema());
        List<String> verbs = relatedResources.getFields().stream().map(Schema.Field::name).toList();
        assertTrue(verbs.containsAll(List.of("set", "add", "remove")), "got: " + verbs);
    }

    /**
     * Schema generation is not enough: Avro reflects an unknown type as a nested record and writes
     * it happily, then fails only on the way back, when it needs a no-arg constructor to build one.
     * Every test above passed while a {@code ZonedDateTime} on {@code EventFields} made every
     * update message undecodable at the consumer. That field is gone, but the next type Avro cannot
     * construct would slip through the same gap — so round-trip a message through the real Pulsar
     * schema, which is what the consumer actually does. The fixture populates the two remaining
     * types with no no-arg constructor, a UUID and a FieldValidationError.
     */
    @Test
    void anUpdateMessageSurvivesAnAvroRoundTrip() {
        var fields = new EventFields();
        fields.setDescription(new ai.intellistream.datahub.helpers.updates.UpdateStringField().set("changed"));
        // Populated on purpose: FieldValidationError has no no-arg constructor, so this pins that
        // validation output stays off the wire.
        fields.getErrors().add(new ai.intellistream.datahub.validation.FieldValidationError("Event", "probe"));
        fields.validateFields();

        var update = new UpdateEventForm();
        update.setId(java.util.UUID.randomUUID());   // UUID has no no-arg constructor either
        update.setUpdate(fields);

        var message = new EventCudMessage();
        message.setTenantId("tenant-1");
        message.getUpdateEvents().add(update);

        var schema = org.apache.pulsar.client.api.Schema.AVRO(EventCudMessage.class);
        EventCudMessage decoded = schema.decode(schema.encode(message));

        assertEquals("tenant-1", decoded.getTenantId());
        assertEquals(1, decoded.getUpdateEvents().size());
        assertEquals("changed",
                decoded.getUpdateEvents().get(0).getUpdate().getDescription().getSet());
    }

    /**
     * An event's time cannot change after creation — the ClickHouse table is partitioned by it, so
     * the mutation is refused outright. The field is therefore absent from the update contract, and
     * a caller sending it gets a 400 naming it rather than a 200 that changed nothing.
     */
    @Test
    void theUpdateFormDoesNotOfferEventTime() {
        Schema patch = unwrapNullable(unwrapNullable(eventCudSchema()
                .getField("updateEvents").schema()).getElementType()
                .getField("update").schema());

        assertNull(patch.getField("eventTime"), "event time is immutable after creation");
        assertNotNull(patch.getField("description"), "other patchable fields are unaffected");
    }
}
