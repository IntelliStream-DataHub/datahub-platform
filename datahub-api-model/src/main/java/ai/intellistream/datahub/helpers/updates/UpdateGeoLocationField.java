// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import ai.intellistream.datahub.models.GeoLocation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Getter;

/**
 * Partial-update instruction for the geolocation field: either set a new GeoJSON geometry, or clear
 * it. Concrete (non-generic) like the sibling update fields — Avro schemas can't describe generics.
 */
@Getter
@Schema(name = "UpdateGeoLocationField", description = "What GeoJSON geometry you want the geolocation set to")
public class UpdateGeoLocationField {

    @Valid
    @Schema(description = "The GeoJSON geometry to set")
    private GeoLocation set;

    @Schema(description = "When you want the value to be null", example = "true")
    private Boolean setNull = false;

    @Schema(hidden = true)
    public UpdateGeoLocationField set(GeoLocation set) {
        this.set = set;
        return this;
    }

    @Schema(hidden = true)
    public UpdateGeoLocationField setNull(Boolean setNull) {
        this.setNull = setNull;
        return this;
    }

    public boolean getSetNull() {
        if (this.setNull == null) return false;
        return this.setNull;
    }
}
