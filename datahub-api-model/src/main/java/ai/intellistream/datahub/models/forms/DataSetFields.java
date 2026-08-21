// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.forms;


import ai.intellistream.datahub.models.validation.ExternalIdRules;
import ai.intellistream.datahub.models.validation.RequiredFieldRules;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import ai.intellistream.datahub.validation.FieldValidationError;

import java.util.ArrayList;
import java.util.List;

/**
 * Timeseries fields mapping for updating the object
 * Generics are not used as Avro Schemas doesn't support schema with generics
 */
@Getter
@Setter
@Schema(name = "Data Set Update Form", description = "Data Set Update Form Object")
public class DataSetFields {

    @JsonAlias({"external_id", "external-id"})
    @JsonProperty("externalId")
    private UpdateStringField externalId = new UpdateStringField();
    private UpdateStringField name = new UpdateStringField();
    private UpdateMapField metadata = new UpdateMapField();
    private UpdateStringField description = new UpdateStringField();
    private UpdateListField labels = new UpdateListField();

    @JsonIgnore
    private List<FieldValidationError> errors = new ArrayList<>();

    public boolean validateUpdateFields(){
        // Stored verbatim — no kebab-to-snake rewrite. See ExternalIdRules.
        ExternalIdRules.validate("DataSet", "dataset", this.externalId.getSet(), errors);

        if(this.name.getSet() != null){
            if(this.name.getSet().length() < 3){
                errors.add(
                        new FieldValidationError(
                                "DataSet",
                                new String[] {"dataset.name.min.length.error"},
                                new Object[] {this.name.getSet().length()},
                                "Name min length is 3 characters.")
                );
            } else if(this.name.getSet().length() > 512){
                errors.add(
                        new FieldValidationError(
                                "DataSet",
                                new String[] {"dataset.name.max.length.error"},
                                new Object[] {this.name.getSet().length()},
                                "Name max length is 512 characters.")
                );
            }
        }

        // A data set is a node too — name and external id are NOT NULL.
        RequiredFieldRules.rejectSetNull("DataSet", "dataset.name.null.error",
                "Name", this.name.getSetNull(), errors);
        RequiredFieldRules.rejectSetNull("DataSet", "dataset.external.id.null.error",
                "ExternalId", this.externalId.getSetNull(), errors);

        return errors.isEmpty();
    }

}
