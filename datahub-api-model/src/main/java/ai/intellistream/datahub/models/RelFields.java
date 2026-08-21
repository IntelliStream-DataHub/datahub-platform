// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;


import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.helpers.updates.UpdateNumberField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import ai.intellistream.datahub.validation.FieldValidationError;

import java.util.ArrayList;
import java.util.List;

/**
 * Relationship fields mapping for updating the object
 * Generics are not used as Avro Schemas doesn't support schema with generics
 */
@Getter
@Setter
public class RelFields {

    private UpdateNumberField start = new UpdateNumberField();
    private UpdateNumberField end = new UpdateNumberField();
    private UpdateStringField fromExternalId = new UpdateStringField();
    private UpdateStringField toExternalId = new UpdateStringField();
    private UpdateStringField relationship = new UpdateStringField();
    private UpdateNumberField relationshipId = new UpdateNumberField();
    private UpdateStringField description = new UpdateStringField();
    private UpdateMapField metadata = new UpdateMapField();

    @JsonIgnore
    private List<FieldValidationError> errors = new ArrayList<>();

    public boolean validateFields(){
        return errors.isEmpty();
    }

}
