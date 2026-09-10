// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.timeseries;


import ai.intellistream.datahub.models.validation.ExternalIdRules;
import ai.intellistream.datahub.models.validation.RequiredFieldRules;
import ai.intellistream.datahub.helpers.updates.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
public class TimeseriesFields {

    @Schema(description = "The name field.")
    private UpdateStringField name = new UpdateStringField();

    @Schema(description = "The name field.")
    private UpdateStringField externalId = new UpdateStringField();

    @Schema(description = "The meta data field.")
    private UpdateMapField metadata = new UpdateMapField();

    @Schema(description = "The unit field.")
    private UpdateStringField unit = new UpdateStringField();

    @Schema(description = "The unit external id field.")
    private UpdateStringField unitExternalId = new UpdateStringField();

    private UpdateStringField description = new UpdateStringField();

    private UpdateNumberField dataSetId = new UpdateNumberField();

    @Schema(description = "The source field.")
    private UpdateStringField source = new UpdateStringField();

    @JsonIgnore
    private List<FieldValidationError> errors = new ArrayList<>();

    public boolean validateUpdateFields(){

        // A timeseries is a node like any other — same `node` table, same external_id_hash unique
        // index — so it gets the same verbatim storage and charset floor as resources and data sets.
        ExternalIdRules.validate("Time Series", "timeseries", this.externalId.getSet(), errors);

        if(this.name.getSet() != null){
            if(this.name.getSet().length() < 3){
                errors.add(
                        new FieldValidationError(
                                "Time Series",
                                new String[] {"timeseries.name.min.length.error"},
                                new Object[] {this.name.getSet().length()},
                                "Name min length is 3 characters.")
                );
            } else if(this.name.getSet().length() > 512){
                errors.add(
                        new FieldValidationError(
                                "Time Series",
                                new String[] {"timeseries.name.max.length.error"},
                                new Object[] {this.name.getSet().length()},
                                "Name max length is 512 characters.")
                );
            }
        }

        // Same node table, same NOT NULL columns as a resource: name and external id can be
        // renamed but not cleared.
        RequiredFieldRules.rejectSetNull("Time Series", "timeseries.name.null.error",
                "Name", this.name.getSetNull(), errors);
        RequiredFieldRules.rejectSetNull("Time Series", "timeseries.external.id.null.error",
                "ExternalId", this.externalId.getSetNull(), errors);

        if(this.unit.getSet() != null){
            if(this.unit.getSet().length() > 64){
                errors.add(
                        new FieldValidationError(
                                "Time Series",
                                new String[] {"timeseries.unit.max.length.error"},
                                new Object[] {this.unit.getSet().length()},
                                "Unit max length is 64 characters.")
                );
            }
        }

        return errors.isEmpty();
    }

}
