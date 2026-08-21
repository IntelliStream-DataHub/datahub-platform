// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.jpa.domains.RelationshipType;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Browse-sized projection of the {@code RelationshipType} JPA entity for
 * {@code edge_list_types}. Returning a projection (rather than the entity) stops the
 * persistence type — and its always-null {@code description}/{@code i18nCode} — from leaking
 * into the MCP tool schema. The name is the token resources use to describe how they connect.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanRelationshipType(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String name
) {
    public static LeanRelationshipType from(RelationshipType rt) {
        return new LeanRelationshipType(rt.getId(), rt.getName());
    }
}
