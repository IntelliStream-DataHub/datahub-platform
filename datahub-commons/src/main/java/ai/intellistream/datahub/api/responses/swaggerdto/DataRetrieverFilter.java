// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name="Data Retriever Filter", description="Retrieves a list of data points from multiple time series in a project.")
@Data
public class DataRetrieverFilter {

    @Schema(description = "The id of the time series object. You need to supply either id or externalId.", example = "5677892", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "Get datapoints starting from, and including, this time.", example = "2020-01-01T01:00Z")
    private String start;

    @Schema(description = "Get datapoints up to, but excluding, this point in time.", example = "2020-01-01T02:00Z")
    private String end;

    @Min(0)
    @Max(100000)
    @Schema(description = "The maximum number of datapoints to return. The maximum is 100000.", example = "100")
    private Integer limit = 0;

    @Schema(description = "The list of aggregations to apply to the data points.", example = "[\"sum\", \"min\", \"max\"]")
    private Collection<String> aggregates = new ArrayList<>();

    @Schema(description = "The time granularity of the data points.", example = "minute")
    private String granularity;

    private Boolean includeOutsidePoints = false;

    @Schema(description = "The cursor to use for pagination. The cursor is returned in the response.")
    private String cursor;

    @Schema(description = "The external id of the time series. You need to supply either id or externalId.", example = "logistics_sap_orders_23", requiredMode = Schema.RequiredMode.REQUIRED)
    private String externalId;

    @Schema(description = "If you have recently updated the data you have queried, enable this to handle data merging.")
    private Boolean mergeDuplicates = false;
}
