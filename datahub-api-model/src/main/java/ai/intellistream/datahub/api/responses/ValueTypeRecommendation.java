// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A suggested timeseries value type for a given unit of measure. The suggestion favours the most
 * compact ClickHouse storage (best compression ratio) that can still represent the unit's typical
 * magnitude and precision faithfully. It is advice for the create-timeseries flow, not a constraint.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "ValueTypeRecommendation",
        description = "A suggested timeseries value type for a unit of measure, chosen for the best "
                + "ClickHouse compression while still representing the data faithfully.")
public class ValueTypeRecommendation {

    @Schema(description = "The unit externalId the recommendation was made for (echoed from the request).",
            example = "temperature_deg_c")
    private String unitExternalId;

    @Schema(description = "Recommended value type. One of BIGINT, FLOAT, FLOAT32, NUMERIC, DECIMAL32, "
            + "TEXT or MIXED.", example = "DECIMAL32")
    private String recommendedValueType;

    @Schema(description = "Why this value type is recommended for the unit.",
            example = "Temperature stays well within Decimal32(4)'s range and needs only a few decimals; "
                    + "Decimal32(4) stores it exactly in 4 bytes with the best compression of the numeric types.")
    private String reason;

    @Schema(description = "True if the unit matched a specific recommendation; false means the generic "
            + "compact default was returned.", example = "true")
    private boolean recognized;
}
