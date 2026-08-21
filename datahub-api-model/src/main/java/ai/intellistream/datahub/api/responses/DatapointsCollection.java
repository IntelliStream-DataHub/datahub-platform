// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Data
@Getter @Setter
@Schema(name="DatapointCollection", description="Object with timeseries reference and a collection of data points.")
public class DatapointsCollection {

    @Schema(description = "The time series id.",
            example = "232345167")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "The time series external id.",
            example = "a4545_well_pump_pressure_a")
    private String externalId;

    private List<DatapointString> datapoints;

    @Schema(description = "If more than 100 000 datapoints are returned, a cursor hash value will be returned",
            example = "0195cd12-7cc7-74af-9ada-67dc22f428f6")
    private String nextCursor;

    @Schema(description = "Time series data unit.",
            example = "Celsius")
    private String unit;

    @Schema(description = "Time series external unit id.",
            example = "volume_barrel_pet_us")
    private String unitExternalId;


}
