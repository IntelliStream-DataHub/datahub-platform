// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.unit;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "UnitConversion", description = "Unit Conversion object, containing multiplier and offset values for converting between units.")
public class UnitConversion {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "The multiplier.", example = "1")
    private Double multiplier;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "The offset.", example = "273.15")
    private Double offset;

}
