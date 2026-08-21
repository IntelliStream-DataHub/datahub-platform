// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * Partial-update instruction for a boolean field: set it, or say nothing and leave it alone.
 *
 * <p>No {@code setNull} counterpart, unlike the string/number/geolocation fields. Its only user is
 * a policy's {@code deactivated}, a non-null column where "clear it" has no meaning — and an
 * inert knob in the published schema is worse than an absent one, because a caller sends it and
 * nothing happens.
 */
@Getter
@Schema(name="UpdateBooleanField", description="What value you want the boolean field set to")
public class UpdateBooleanField {

    @Schema(description = "What value you want the boolean field set to. Omit the field entirely to leave it unchanged.", example = "true")
    private Boolean set;

    @Schema(hidden = true)
    public UpdateBooleanField set(Boolean set) {
        this.set = set;
        return this;
    }
}
