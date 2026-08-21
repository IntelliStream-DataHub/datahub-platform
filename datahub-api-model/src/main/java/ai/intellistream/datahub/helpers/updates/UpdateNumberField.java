// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name="UpdateNumberField", description="What value you want the number field set to")
public class UpdateNumberField {

    @Schema(description = "What value you want the number field set to", example = "Vidar")
    private Long set;

    @Schema(description = "When you want the value to be null", example = "true")
    private Boolean setNull = false;

    @Schema(hidden = true)
    public UpdateNumberField set(Long set) {
        this.set = set;
        return this;
    }

    @Schema(hidden = true)
    public UpdateNumberField setNull(Boolean setNull) {
        this.setNull = setNull;
        return this;
    }

    // Null-safe primitive accessor, mirroring UpdateStringField. Lombok's @Getter would otherwise
    // expose a boxed Boolean that is null when a client sends `"setNull": null`, forcing every
    // caller to write a `!= null` guard — and getting that guard wrong is exactly how a resource
    // update came to null the dataset on every call (the field defaults to false, so `!= null`
    // was always true). Returning a guarded primitive makes `if (field.getSetNull())` correct.
    public boolean getSetNull() {
        if (this.setNull == null) return false;
        return this.setNull;
    }
}
