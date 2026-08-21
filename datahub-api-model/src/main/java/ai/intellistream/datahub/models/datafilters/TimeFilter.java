// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.json.TimestampDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.ZonedDateTime;

/**
 * An inclusive time window, used by every timestamp criterion on every filter — {@code createdTime},
 * {@code lastUpdatedTime}, and {@code eventTime}.
 *
 * <p>Either bound may be omitted: {@code min} alone is "since", {@code max} alone is "until", both
 * is a range. Values are ISO-8601 or epoch millis, resolved by {@link TimestampDeserializer}.
 *
 * <p>There used to be two empty subclasses of this, {@code CreatedTimeFilter} and
 * {@code LastUpdatedTimeFilter}, which added no field and no behaviour — the type a window was
 * declared as carried no information, since the field name already said which column it applied to.
 * The giveaway was {@code EventFilter.eventTime}, which was typed {@code CreatedTimeFilter}: a
 * third meaning wearing the name of the first. They also shared this class's {@code @Schema(name)},
 * so all three were already one schema in the OpenAPI document.
 */
@Schema(name = "TimeFilter", description = "An inclusive time window; either bound may be omitted.")
@Data
public class TimeFilter {

    @Schema(description = "The minimum ISO 8601 time or epoch time.", example = "2024-01-01T00:00Z or 1710069401321")
    @JsonDeserialize(using = TimestampDeserializer.class)
    private ZonedDateTime min;

    @Schema(description = "The maximum ISO 8601 time or epoch time.", example = "2024-01-02T03:00Z or 1714461401221")
    @JsonDeserialize(using = TimestampDeserializer.class)
    private ZonedDateTime max;
}
