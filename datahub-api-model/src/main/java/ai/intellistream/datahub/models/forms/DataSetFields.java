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

import ai.intellistream.datahub.models.validation.SizeRules;
import ai.intellistream.datahub.models.validation.FieldLimits;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Stream;
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

        // The caps create already enforces through NodeModel's annotations. Update validates by
        // hand, so anything not repeated here simply was not enforced: a data set could be updated
        // to hold a description, a metadata map and a label list that create would have refused.
        SizeRules.checkLength("DataSet", "dataset.description.max.length.error", "Description",
                this.description.getSet(), FieldLimits.DESCRIPTION_MAX, errors);

        SizeRules.checkMetadata("DataSet", "dataset", this.metadata, errors);

        SizeRules.checkCount("DataSet", "dataset.too.many.labels", "Labels",
                this.labels.getSet(), FieldLimits.LABELS_MAX, errors);
        SizeRules.checkCount("DataSet", "dataset.too.many.labels", "Labels",
                this.labels.getAdd(), FieldLimits.LABELS_MAX, errors);

        Stream.of(this.labels.getSet(), this.labels.getAdd())
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .forEach(label -> SizeRules.checkLength("DataSet", "dataset.label.max.length.error",
                        "Label", label, FieldLimits.LABEL_LENGTH_MAX, errors));

        return errors.isEmpty();
    }

}
