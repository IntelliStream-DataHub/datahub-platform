// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;


import ai.intellistream.datahub.helpers.updates.UpdateGeoLocationField;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.helpers.updates.UpdateNumberField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import ai.intellistream.datahub.validation.FieldValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resource fields mapping for updating the object
 * Generics are not used as Avro Schemas doesn't support schema with generics
 */
@Getter
@Setter
public class ResourceFields {

    private UpdateStringField externalId = new UpdateStringField();
    private UpdateStringField name = new UpdateStringField();
    private UpdateStringField description = new UpdateStringField();
    private UpdateNumberField dataSetId = new UpdateNumberField();

    private UpdateMapField metadata = new UpdateMapField();

    private UpdateStringField source = new UpdateStringField();
    private UpdateListField labels = new UpdateListField();
    private UpdateGeoLocationField geoLocation = new UpdateGeoLocationField();


    @JsonIgnore
    private List<FieldValidationError> errors = new ArrayList<>();

    public boolean validateFields(){
        // Stored verbatim — no kebab-to-snake rewrite. See ExternalIdRules.
        ExternalIdRules.validate("Resource", "resource", this.externalId.getSet(), errors);

        if(this.name.getSet() != null){
            if(this.name.getSet().length() < 3){
                errors.add(
                        new FieldValidationError(
                                "Resource",
                                new String[] {"resource.name.min.length.error"},
                                new Object[] {this.name.getSet().length()},
                                "Name min length is 3 characters.")
                );
            } else if(this.name.getSet().length() > 512){
                errors.add(
                        new FieldValidationError(
                                "Resource",
                                new String[] {"resource.name.max.length.error"},
                                new Object[] {this.name.getSet().length()},
                                "Name max length is 512 characters.")
                );
            }
        }

        // node.name and node.external_id are NOT NULL, so neither can be cleared. This check used
        // to hang off the `name.set != null` branch above, which meant it only fired for requests
        // that were also setting a name — the one shape that never asks to clear it. A bare
        // {"name": {"setNull": true}} fell straight through and 200'd without changing anything.
        RequiredFieldRules.rejectSetNull("Resource", "resource.name.null.error",
                "Name", this.name.getSetNull(), errors);
        RequiredFieldRules.rejectSetNull("Resource", "resource.external.id.null.error",
                "ExternalId", this.externalId.getSetNull(), errors);

        if(this.source.getSet() != null){
            if(this.source.getSet().length() > 64){
                errors.add(
                        new FieldValidationError(
                                "Resource",
                                new String[] {"resource.source.max.length.error"},
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

        if(this.geoLocation.getSet() != null && !this.geoLocation.getSet().isValidGeoJson()){
            errors.add(
                    new FieldValidationError(
                            "Resource",
                            new String[] {"geolocation.invalid.geojson"},
                            new Object[] {},
                            "Geolocation must be a valid GeoJSON geometry.")
            );
        }

        return errors.isEmpty();
    }

}
