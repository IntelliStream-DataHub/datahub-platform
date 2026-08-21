// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Lean envelope for MCP list/search results — the counterpart to the drill-down half of
 * {@code event_filter}'s {@code EventQueryResult}. Replaces {@code DataWrapper} on the tool
 * path so callers get an explicit {@code returned} count and a {@code truncated} flag instead
 * of a silently-capped {@code items} array.
 *
 * <p>{@link JsonInclude.Include#NON_EMPTY} keeps {@code truncated:false} and an empty
 * {@code items} out of the payload.
 *
 * @param items     the lean projections (see the {@code Lean*} records in this package)
 * @param returned  how many items are in this response
 * @param truncated true when the result hit the requested cap, so more may match
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record McpList<T>(List<T> items, int returned, boolean truncated) {

    /**
     * Wrap a result list, marking it truncated when it filled the requested cap. Mirrors
     * {@code EventService.queryEvents}: hitting the limit is treated as "possibly more".
     */
    public static <T> McpList<T> of(List<T> items, int requestedLimit) {
        return new McpList<>(items, items.size(), items.size() >= requestedLimit);
    }
}
