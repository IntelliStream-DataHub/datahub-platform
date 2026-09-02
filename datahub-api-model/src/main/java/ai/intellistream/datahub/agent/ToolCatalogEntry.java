// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.agent;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the platform's MCP tool catalogue: what the tool is called, what it does to the
 * data, and which MCP server serves it.
 *
 * @param name       the MCP tool name, e.g. {@code timeseries_search}
 * @param capability whether it reads or writes
 * @param server     which MCP server advertises it — {@code datahub-api} or
 *                   {@code datahub-analysis}. Informational for a client, but it is what explains
 *                   a tool going missing when a sibling service is down.
 */
@Schema(name = "ToolCatalogEntry",
        description = "An MCP tool an agent may be allowed to use, and what it does to the data.")
public record ToolCatalogEntry(
        @Schema(description = "MCP tool name.", example = "timeseries_search") String name,
        @Schema(description = "Whether the tool reads or writes.") ToolCapability capability,
        @Schema(description = "Which MCP server serves it.", example = "datahub-api") String server) {
}
