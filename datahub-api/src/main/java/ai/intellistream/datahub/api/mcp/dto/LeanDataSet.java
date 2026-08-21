// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.models.DataSetModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Browse-sized projection of {@link DataSetModel} for the list/search MCP tools. Drops the
 * usually-empty {@code policies}, {@code metadata}, and {@code connectedDataSets} collections
 * and the audit timestamps — the id, externalId, name, and description are what the LLM needs
 * to pick a dataset (its id feeds {@code timeseries_create}).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanDataSet(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String externalId,
        String name,
        String description
) {
    public static LeanDataSet from(DataSetModel d) {
        return new LeanDataSet(d.getId(), d.getExternalId(), d.getName(), d.getDescription());
    }
}
