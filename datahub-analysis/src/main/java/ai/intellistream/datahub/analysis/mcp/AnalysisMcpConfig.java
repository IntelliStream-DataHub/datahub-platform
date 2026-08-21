// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * below — if it's not in this list, the MCP server doesn't advertise it.
 */
@Configuration
public class AnalysisMcpConfig {

    @Bean
    public ToolCallbackProvider analysisToolCallbacks(AnalysisMcpTools analysisTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(analysisTools)
                .build();
    }
}
