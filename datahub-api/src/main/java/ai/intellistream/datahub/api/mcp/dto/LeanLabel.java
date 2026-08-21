// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.jpa.domains.Label;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Browse-sized projection of the {@code Label} JPA entity for {@code label_list}. Returning a
 * projection (rather than the entity) also stops the persistence type from leaking into the
 * MCP tool schema. Keeps the name (what gets attached to resources), the id, and the UI color;
 * drops description/i18nCode/audit fields.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanLabel(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String name,
        String color
) {
    public static LeanLabel from(Label l) {
        return new LeanLabel(l.getId(), l.getName(), l.getColor());
    }
}
