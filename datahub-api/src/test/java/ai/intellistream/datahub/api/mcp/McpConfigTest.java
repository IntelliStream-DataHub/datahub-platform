// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp;

import ai.intellistream.datahub.api.mcp.tools.DatasetMcpTools;
import ai.intellistream.datahub.api.mcp.tools.EdgeMcpTools;
import ai.intellistream.datahub.api.mcp.tools.EventMcpTools;
import ai.intellistream.datahub.api.mcp.tools.LabelMcpTools;
import ai.intellistream.datahub.api.mcp.tools.ResourceMcpTools;
import ai.intellistream.datahub.api.mcp.tools.TimeseriesMcpTools;
import ai.intellistream.datahub.api.mcp.tools.UnitMcpTools;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every advertised tool carries MCP hints, and the hints match what the tool does: clients
 * use them to decide which calls need the user's confirmation. Building the specifications
 * never calls a tool, so the tools get no services.
 */
class McpConfigTest {

    private final Map<String, McpSchema.ToolAnnotations> hints = new McpConfig().datahubMcpTools(
                    new TimeseriesMcpTools(null),
                    new DatasetMcpTools(null, null, null, null),
                    new ResourceMcpTools(null),
                    new EdgeMcpTools(null, null, null),
                    new EventMcpTools(null),
                    new UnitMcpTools(null),
                    new LabelMcpTools(null))
            .stream()
            .collect(Collectors.toMap(spec -> spec.tool().name(), spec -> spec.tool().annotations()));

    @Test
    void readsAreReadOnly() {
        assertThat(hints).hasSize(37);
        hints.forEach((name, h) -> {
            boolean reads = name.matches(".*_(get|get_latest|list|list_types|search|filter|fetch_.*)");
            assertThat(h.readOnlyHint()).as(name).isEqualTo(reads);
            assertThat(h.openWorldHint()).as(name).isFalse();
        });
    }

    @Test
    void writesSayWhetherTheyOverwrite() {
        assertThat(hints.get("event_create").destructiveHint()).isFalse();
        assertThat(hints.get("event_create").idempotentHint()).isFalse();
        assertThat(hints.get("resource_update").destructiveHint()).isTrue();
        assertThat(hints.get("timeseries_delete").destructiveHint()).isTrue();
        assertThat(hints.get("timeseries_delete").idempotentHint()).isTrue();
        assertThat(hints.get("timeseries_send_datapoint").destructiveHint()).isTrue();
    }
}
