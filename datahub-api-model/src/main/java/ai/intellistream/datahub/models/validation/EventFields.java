// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;


import ai.intellistream.datahub.helpers.updates.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import ai.intellistream.datahub.validation.FieldValidationError;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Event fields mapping for updating the object
 * Generics are not used as Avro Schemas doesn't support schema with generics
 */
@Getter
@Setter
@Schema(name = "Update Event Fields", description = "Update Event Fields")
public class EventFields {

    /*
     * There is deliberately no eventTime here. An event's time cannot be changed after creation:
     * the ClickHouse events table is PARTITION BY toYYYYMM(event_time), and a mutation cannot move
     * a row between partitions, so `ALTER TABLE events UPDATE event_time` is refused outright with
     * CANNOT_UPDATE_COLUMN. The field used to exist and was accepted, echoed back with the new
     * value, and then discarded — the api reported a change it had no way to make. Removing it
     * means a caller sending eventTime now gets a 400 naming the field rather than a false 200.
     *
     * There is deliberately no externalId either. An event's externalId is its identity, not a
     * property: KVRocks maps hash(externalId) to the SET of event UUIDs behind it, because events
     * sharing an externalId are the lifecycle of one logical event. A rename therefore has no
     * per-event meaning — moving the whole key would silently take every sibling event along, and
     * the old code did not even have the old value when the caller identified the event by UUID,
     * so the mapping was left untouched and the "renamed" event stopped resolving by either id.
     * Like eventTime, absence means a caller sending it gets a 400 naming the field. The UUID id
     * was never patchable. To re-key an event, create a new one and delete the old.
     */

    private UpdateStringField description = new UpdateStringField();
    private UpdateStringField type = new UpdateStringField();
    private UpdateStringField subType = new UpdateStringField();
    private UpdateStringField status = new UpdateStringField();
    private UpdateNumberField dataSetId = new UpdateNumberField();
    private UpdateMapField metadata = new UpdateMapField();
    private UpdateStringField source = new UpdateStringField();
    private UpdateIdCollectionListField relatedResources = new UpdateIdCollectionListField();


    /**
     * Validation output, read by the caller immediately after {@link #validateFields()} on the same
     * object. {@code transient} for the same reason the event time is not cached here: this form
     * travels inside {@code EventCudMessage} over Pulsar, and {@code FieldValidationError} has no
     * no-arg constructor, so a populated list would make the message undecodable at the consumer.
     */
    @JsonIgnore
    private transient List<FieldValidationError> errors = new ArrayList<>();

    public boolean validateFields(){
        if(this.type.getSet() != null){
            if(this.type.getSet().length() > 128){
                errors.add(
                        new FieldValidationError(
                                "Event",
                                new String[] {"event.type.max.length.error"},
                                new Object[] {this.type.getSet().length()},
                                "Type max length is 128 characters.")
                );
            }
        }

        if(this.subType.getSet() != null){
            if(this.subType.getSet().length() > 128){
                errors.add(
                        new FieldValidationError(
                                "Event",
                                new String[] {"event.subType.max.length.error"},
                                new Object[] {this.subType.getSet().length()},
                                "Type max length is 128 characters.")
                );
            }
        }

        if(this.status.getSet() != null){
            if(this.status.getSet().length() > 128){
                errors.add(
                        new FieldValidationError(
                                "Event",
                                new String[] {"event.status.max.length.error"},
                                new Object[] {this.type.getSet().length()},
                                "Type max length is 128 characters.")
                );
            }
        }

        if(this.source.getSet() != null){
            if(this.source.getSet().length() > 64){
                errors.add(
                        new FieldValidationError(
                                "Event",
                                new String[] {"event.source.max.length.error"},
                                new Object[] {this.source.getSet().length()},
                                "Source max length is 64 characters.")
                );
            }
        }

        if(this.metadata.getSet() != null){
            Map<String, String> metadata = this.metadata.getSet();
            if(metadata.containsKey("")){
                // Just remove empty key, no need to add error message
                this.metadata.getSet().remove("");
            }
        }

        // An entry with neither side is unresolvable, so reject it here rather than letting the
        // service raise it — the caller gets one validation response with every bad field in it.
        Stream.of(this.relatedResources.getSet(), this.relatedResources.getAdd(), this.relatedResources.getRemove())
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(it -> it == null || (it.getId() == null && it.getExternalId() == null))
                .forEach(it -> errors.add(
                        new FieldValidationError(
                                "Event",
                                new String[] {"event.related.resource.empty.error"},
                                new Object[] {},
                                "A related resource must have an id or an externalId.")
                ));

        // type is required on create and stored non-nullable by ClickHouse, as
        // LowCardinality(String). Clearing it was damaging: the service honoured it, ClickHouse
        // coerced the null to an empty string, and every later read of that event failed against a
        // model that declares type required. The event became unreadable through the very client
        // that wrote it. (externalId and event_time are the other two required fields, but neither
        // can be updated at all — see the class comment.)
        RequiredFieldRules.rejectSetNull("Event", "event.type.null.error",
                "Type", this.type.getSetNull(), errors);

        return errors.isEmpty();
    }

}
