// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name="UpdateStringField", description="What value you want the string field set to")
public class UpdateStringField {

    @Schema(description = "What value you want the string field set to", example = "Vidar")
    private String set;

    @Schema(description = "When you want the value to be null", example = "true")
    private Boolean setNull = false;

    @Schema(hidden = true)
    public UpdateStringField set(String set) {
        this.set = set;
        return this;
    }

    @Schema(hidden = true)
    public UpdateStringField setNull(Boolean setNull) {
        this.setNull = setNull;
        return this;
    }

    public boolean getSetNull(){
        if(this.setNull == null) return false;
        return this.setNull;
    }
}
