// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp;

import ai.intellistream.datahub.api.mcp.tools.DatasetMcpTools;
import ai.intellistream.datahub.api.mcp.tools.EdgeMcpTools;
import ai.intellistream.datahub.api.mcp.tools.EventMcpTools;
import ai.intellistream.datahub.api.mcp.tools.LabelMcpTools;
import ai.intellistream.datahub.api.mcp.tools.ResourceMcpTools;
import ai.intellistream.datahub.api.mcp.tools.TimeseriesMcpTools;
import ai.intellistream.datahub.api.mcp.tools.UnitMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP (Model Context Protocol) server wiring for datahub-api.
 *
 * <h3>Transport</h3>
 * Served over HTTP via Spring AI's WebMVC transport (stateless mode — see
 * {@code spring.ai.mcp.server.protocol} in {@code application-dev.yml}). The MCP
 * endpoint is just another Spring MVC endpoint under {@code /mcp/*}, so the existing
 * {@code SecurityFilterChain} handles authentication and authorisation without any
 * MCP-specific bypass.
 *
 * <h3>Authentication</h3>
 * MCP clients present a standard OAuth2 Bearer JWT in the {@code Authorization} header
 * on each request. The existing {@code JwtDecoder} + {@code OrganizationValidator}:
 * <ul>
 *     <li>verify the token signature and issuer,</li>
 *     <li>extract the {@code organization.*.id} claim and populate {@code TenantContext}
 *         via {@code OrganizationValidator} (same code path used for REST calls), and</li>
 *     <li>derive the {@code realm_access.roles} into granted authorities, which the
 *         security filter chain requires to include {@code ROLE_DATAHUB_ACCESS} for every
 *         non-public request (see {@code SecurityConfig}). MCP tools are served as MVC
 *         endpoints on that same chain, so this role check is applied before any tool runs.</li>
 * </ul>
 * Tool methods execute on the request thread, so downstream {@code @Service} calls that
 * depend on {@code TenantContext.getTenantId()} just work. {@code RequestStateCleanupFilter}
 * clears the {@code TenantContext} at request end to prevent thread-pool leaks — do not
 * remove.
 *
 * <h3>Adding tools</h3>
 * Declare a Spring bean in the {@code mcp.tools} sub-package and annotate its public
 * methods with {@link org.springframework.ai.tool.annotation.Tool @Tool}. Register the
 * new bean in {@link #datahubMcpTools(TimeseriesMcpTools, DatasetMcpTools)}. That's it —
 * Spring AI introspects the methods and builds the MCP tool schema from parameter types,
 * {@code @ToolParam} descriptions, and Jackson's view of the return type.
 *
 * <h3>Tool naming convention</h3>
 * {@code <domain>_<action>} in snake_case (e.g. {@code timeseries_create}). Keeps the
 * MCP listing readable for both humans and the LLM.
 *
 * <h3>No elicitation (yet)</h3>
 * The server runs in {@code STATELESS} protocol mode (see
 * {@code spring.ai.mcp.server.protocol} in {@code application-dev.yml}), which means
 * tools cannot open a server-to-client channel to ask the user to confirm a mutating
 * action ({@code elicitation/create}). The MCP spec's elicitation capability requires
 * a {@code STATEFUL} server, which in turn requires session affinity on the API load
 * balancer and changes the security / thread model (JWT validated once at SSE open;
 * {@code TenantContext} would need to be propagated to SSE writer threads). The
 * tradeoff wasn't worth it for an entity-metadata surface that is expected to be
 * mostly-static — updates here are rare. If we ever need real user-in-the-loop
 * confirmation for mutating tools, switch to {@code STATEFUL}, migrate these @Tool
 * methods to {@code @McpTool}, inject {@code McpSyncRequestContext}, and call
 * {@code context.elicit(...)} before performing the write.
 */
@Configuration
public class McpConfig {

    /**
     * The single {@link ToolCallbackProvider} bean Spring AI's MCP auto-configuration
     * uses to publish tools to clients. Add every {@code @Tool}-bearing bean to this
     * list — if it's not here, the MCP server doesn't advertise it.
     */
    @Bean
    public ToolCallbackProvider datahubMcpTools(
            TimeseriesMcpTools timeseriesTools,
            DatasetMcpTools datasetTools,
            ResourceMcpTools resourceTools,
            EdgeMcpTools edgeTools,
            EventMcpTools eventTools,
            UnitMcpTools unitTools,
            LabelMcpTools labelTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        timeseriesTools,
                        datasetTools,
                        resourceTools,
                        edgeTools,
                        eventTools,
                        unitTools,
                        labelTools
                )
                .build();
    }
}
