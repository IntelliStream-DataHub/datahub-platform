// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.timeseries.Timeseries;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.Map;

/**
 * Browse-sized projection of {@link Timeseries} for the list/search MCP tools. Drops the
 * full DTO's low-signal fields — {@code tableEngine}, {@code valueType}, empty
 * {@code securityCategories}/{@code relatedResources}, and both audit timestamps — which the
 * LLM does not need to pick a series. Use {@code timeseries_get} for the full record.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanTimeseries(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String externalId,
        String name,
        @JsonSerialize(using = ToStringSerializer.class) Long dataSetId,
        String unit,
        String description,
        Map<String, String> metadata
) {
    public static LeanTimeseries from(Timeseries t) {
        return new LeanTimeseries(
                t.getId(), t.getExternalId(), t.getName(), t.getDataSetId(),
                t.getUnit(), t.getDescription(), t.getMetadata());
    }
}
