// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.unit;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
import java.util.TreeSet;

@NoArgsConstructor
@Data
@Schema(name = "Unit", description = "Unit object, that describes to properties of the unit type.")
public class UnitModel {

    @Schema(description = "The id of the unit.", example = "5677892")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @NotNull
    @Size(min= 3, max = 256)
    @Schema(description = "The parent external id of the unit.", example = "temperature_celsius")
    private String externalId;

    @NotNull
    @Size(min = 1, max = 64)
    @Schema(description = "The name of the unit.", example = "Celsius")
    private String name;

    @Size(min = 1, max = 256)
    @Schema(description = "The full name of the unit.", example = "Celsius")
    private String longName;

    @Size(min = 1, max = 32)
    @Schema(description = "The symbol of the unit.", example = "°C")
    private String symbol;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "The description of the unit.", example = "Something about celsius...")
    private String description;

    @Schema(description = "A list of alternative aliases (names) for the unit.", example = "[\"c\", \"C\", \"Celsius\"]")
    private Set<String> aliasNames = new TreeSet<>();

    @Schema(description = "Specifies the physical quantity the unit.", example = "Temperature")
    private String quantity;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "An Unit Conversion object with multiplier and offset values for converting between units.")
    private UnitConversion conversion;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Source of the unit specification.", example = "qudt.org")
    private String source;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Reference to the source of the specification", example = "https://qudt.org/vocab/unit/DEG_C")
    private String sourceReference;
}
