// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Delete data points request body.")
@JsonPropertyOrder({ "id", "externalId", "inclusiveBegin", "exclusiveEnd" })
public class DeleteDatapoint {

    @Schema(description = "The id of the time series. Give this or `externalId`.",
            example = "123466453"
    )
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "The external id of the time series. Give this or `id`.",
            example = "a4545_well_pump_pressure_a"
    )
    private String externalId;

    // Both bounds are optional, and neither carries @NotNull: the endpoint takes the body without
    // @Valid, so a constraint here would never have been enforced, and it would have advertised a
    // rule the service does not apply. An absent bound means "unbounded on that side".
    @Schema(description = """
            Start of the window to clear, inclusive. Either ISO-8601 or epoch milliseconds. \
            Optional: leave it out to delete everything up to `exclusiveEnd`, and leave both \
            bounds out to clear every data point of the series.""",
            example = "2026-01-01T00:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private String inclusiveBegin;

    @Schema(description = """
            End of the window to clear, exclusive. Either ISO-8601 or epoch milliseconds. \
            Optional: leave it out to delete everything from `inclusiveBegin` onward.""",
            example = "2026-02-01T00:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private String exclusiveEnd;
}
