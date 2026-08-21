// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.models.unit.UnitModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Browse-sized projection of {@link UnitModel} for {@code unit_list}. Keeps just what the LLM
 * needs to attach a physical unit to a timeseries — the externalId to pass, plus a
 * human-recognisable name/symbol/quantity — and drops longName, aliases, conversion, and
 * source provenance. Use {@code unit_get} for the full catalogue entry.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanUnit(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String externalId,
        String name,
        String symbol,
        String quantity
) {
    public static LeanUnit from(UnitModel u) {
        return new LeanUnit(u.getId(), u.getExternalId(), u.getName(), u.getSymbol(), u.getQuantity());
    }
}
