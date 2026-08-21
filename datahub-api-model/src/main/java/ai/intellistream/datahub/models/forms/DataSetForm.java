// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.forms;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(name = "Data Set Form", description = "Data Set Form Object")
public class DataSetForm {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    // The external-id charset floor (see TextValidator.validateExternalIdCharset). Was
    // snake_case-only; relaxed so industrial tags (COM-99-PT-1034, =K1-M3+B02) survive
    // verbatim. A stricter house convention is now a configurable naming policy, not a
    // hard-coded bean constraint.
    @Pattern(regexp = "[A-Za-z0-9._:+=-]+")
    private String externalId;

    private DataSetFields update;

    public void setExternalId(String externalId){
        this.externalId = externalId;
    }

    public DataSetFields getUpdate(){
        // A request may identify a dataset without carrying an update block; callers must handle a
        // null update rather than have this getter NPE (which surfaced as a 500 instead of a 400).
        if(this.update == null){
            return null;
        }
        // If same external id name, just remove it
        if(this.update.getExternalId().getSet() != null && this.update.getExternalId().getSet().equals(this.externalId)){
            this.update.setExternalId( new UpdateStringField() );
        }
        return this.update;
    }

}
