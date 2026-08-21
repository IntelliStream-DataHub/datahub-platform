// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.timeseries;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.helpers.text.TextValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class UpdateTimeseries {

    @Schema(description = "The id of the time series.",
            example = "123466453"
    )
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "The external id of the time series.",
            example = "a4545_well_pump_pressure_a"
    )
    // The external-id charset floor (see TextValidator.validateExternalIdCharset). Was
    // snake_case-only; relaxed so industrial tags (COM-99-PT-1034, =K1-M3+B02) survive
    // verbatim. A stricter house convention is now a configurable naming policy, not a
    // hard-coded bean constraint.
    @Pattern(regexp = "[A-Za-z0-9._:+=-]+")
    private String externalId;

    TimeseriesFields update = new TimeseriesFields();

    public UpdateTimeseries setId(Long id) {
        this.id = id;
        return this;
    }

    public UpdateTimeseries setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public UpdateTimeseries setUpdate(TimeseriesFields update) {
        this.update = update;
        return this;
    }

    public boolean hasId() {
        if(id == null) return false;
        return id >= 1L;
    }
}
