// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
@Schema(name="Timeseries With Datapoints", description="Timeseries with datapoints")
public class DataCollectionDatapoint {

    @Schema(description = "The id of the time series object.", example = "5677892")
    private Long id;

    @Schema(description = "The external id of the time series.", example = "logistics_sap_orders_11")
    private String externalId;

    private List<DatapointDTO> datapoints;

}
