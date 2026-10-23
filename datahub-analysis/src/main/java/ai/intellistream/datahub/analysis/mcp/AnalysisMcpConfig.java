// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * MCP server wiring for datahub-analysis — the analysis engine's agent-facing surface,
 * mirroring datahub-api's {@code McpConfig}.
 *
 * <h3>Why this service hosts its own MCP endpoint</h3>
 * The dependency runs analysis → api (this service gathers its data from the api via the
 * Java SDK). Publishing these tools from datahub-api would invert that into a runtime cycle;
 * hosting them here keeps the direction intact, and MCP clients simply connect to both servers.
 *
 * <h3>Transport &amp; authentication</h3>
 * Same shape as the api's: Spring AI's WebMVC transport in {@code STATELESS} protocol mode
 * ({@code spring.ai.mcp.server.*} in {@code application.properties}), served at {@code POST /mcp}
 * as a normal Spring MVC endpoint. {@code SecurityConfig}'s {@code anyRequest()} rule therefore
 * covers it: a valid user JWT with {@code ROLE_DATAHUB_ACCESS}, no MCP-specific bypass. Tool
 * methods run on the request thread, so {@code AnalysisApiClientFactory.forCurrentUser()} reads
 * the caller's JWT from the security context and forwards it to the api — the api's per-dataset
 * ACLs apply to tool calls exactly as they do to the {@code /analysis} REST endpoint.
 *
 * <h3>Adding tools</h3>
 * Annotate public methods on a bean in this package with {@code @Tool} and register the bean
 * below — if it's not in this list, the MCP server doesn't advertise it. Classify each new tool
 * in {@link #hintsFor}; startup fails for one that has no hints. As in the api's config, the
 * tools are converted to specifications here rather than handed over as a
 * {@code ToolCallbackProvider}, because {@code @Tool} cannot carry MCP's tool annotations.
 */
@Configuration
public class AnalysisMcpConfig {

    /** Reads only; repeating it changes nothing. Closed-world: it reads this DataHub tenant. */
    private static final ToolAnnotations READ_ONLY = new ToolAnnotations(null, true, false, true, false, null);

    @Bean
    public List<SyncToolSpecification> analysisToolSpecifications(AnalysisMcpTools analysisTools) {
        var callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(analysisTools)
                .build()
                .getToolCallbacks();
        return Arrays.stream(callbacks)
                .map(callback -> {
                    var spec = McpToolUtils.toStatelessSyncToolSpecification(callback, null);
                    McpSchema.Tool tool = spec.tool();
                    return new SyncToolSpecification(new McpSchema.Tool(tool.name(), tool.title(),
                            tool.description(), tool.inputSchema(), tool.outputSchema(),
                            hintsFor(tool.name()), tool.meta(), tool.icons()), spec.callHandler());
                })
                .toList();
    }

    private static ToolAnnotations hintsFor(String tool) {
        return switch (tool) {
            case "analysis_related_series" -> READ_ONLY;
            default -> throw new IllegalStateException(
                    "MCP tool '" + tool + "' has no hints; classify it in AnalysisMcpConfig.hintsFor");
        };
    }
}
