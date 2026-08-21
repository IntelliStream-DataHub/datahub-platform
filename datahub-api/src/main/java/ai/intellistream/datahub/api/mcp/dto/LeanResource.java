// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.models.Resource;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.List;

/**
 * Browse-sized projection of {@link Resource} for the list/search and graph MCP tools. Drops
 * the {@code isRoot=false} noise, the embedded {@code relations} edge list, empty
 * {@code metadata}, {@code geoLocation}, and both audit timestamps. Use {@code resource_get}
 * for the full record, or {@code resource_fetch_related} for the edges.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanResource(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String externalId,
        String name,
        @JsonSerialize(using = ToStringSerializer.class) Long dataSetId,
        String description,
        String source,
        List<String> labels
) {
    public static LeanResource from(Resource r) {
        return new LeanResource(
                r.getId(), r.getExternalId(), r.getName(), r.getDataSetId(),
                r.getDescription(), r.getSource(), r.getLabels());
    }
}
