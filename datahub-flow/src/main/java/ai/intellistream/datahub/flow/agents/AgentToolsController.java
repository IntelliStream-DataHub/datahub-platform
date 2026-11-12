// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.agents;

import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.llm.LlmToolDef;
import ai.intellistream.datahub.llm.mcp.McpException;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import ai.intellistream.datahub.llm.mcp.StreamableMcpClient;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools an agent may be given: what the platform's MCP servers advertise, with their hints, as
 * the caller, and what the tool servers the organization registered advertise, as their own
 * credential. Asked with the caller's own token, the platform's list is what that person could
 * call; the agent then runs as its deployed identity, whose access may differ, and a tool that
 * identity may not call fails in the run, as any call does.
 *
 * <p>A platform server that does not answer is a 503: without its tools the picker is no use. A
 * registered server that does not answer is reported beside the tools of the rest, so one dead
 * server does not blank the form, and the person editing sees which one it is.
 */
@RestController
public class AgentToolsController {

    /**
     * One tool as the picker shows it. {@code readOnly} and {@code destructive} are the server's
     * hints, null when it gave none; {@code external} says the server is one the organization
     * registered, whose tools are named {@code <server>__<tool>}.
     */
    public record Tool(String server, String name, String description, Boolean readOnly, Boolean destructive,
                       boolean external) {
    }

    /** A registered server whose tools could not be listed, and what it said or did not. */
    public record Unavailable(String server, String error) {
    }

    private final FlowService flows;
    private final List<StatelessMcpClient> servers;
    private final ExternalMcp external;
    private final TenantConfigService tenants;
    private final TenantConfigWriter tenantConfig;

    public AgentToolsController(FlowService flows, List<StatelessMcpClient> mcpServers, ExternalMcp external,
                                TenantConfigService tenants, TenantConfigWriter tenantConfig) {
        this.flows = flows;
        this.servers = mcpServers;
        this.external = external;
        this.tenants = tenants;
        this.tenantConfig = tenantConfig;
    }

    /**
     * {@code {"tools": [...], "unavailable": [...]}}: the platform's servers first, in the order a
     * run looks a name up, then the registered ones by name. Takes {@code /flows/read}; 503 when a
     * platform server or the registry does not answer.
     */
    @GetMapping("/agents/tools")
    public Map<String, Object> tools() {
        flows.requireRead();
        String token = CallerAccess.currentJwt().getTokenValue();
        List<Tool> tools = new ArrayList<>();
        for (StatelessMcpClient server : servers) {
            List<LlmToolDef> advertised;
            try {
                advertised = server.listTools(token);
            } catch (McpException e) {
                throw FlowProblems.toolsUnavailable("The tools of " + server.name() + " could not be listed: "
                        + e.getMessage());
            }
            for (LlmToolDef tool : advertised) {
                tools.add(new Tool(server.name(), tool.name(), tool.description(), tool.readOnly(), tool.destructive(), false));
            }
        }
        Map<String, TenantFlowSection.McpServer> registered;
        try {
            Tenant tenant = tenants.getConfig(TenantContext.getTenantId());
            registered = tenant == null ? Map.of()
                    : TenantFlowSection.mcpServers(tenantConfig.current(tenant.getOrganizationName()));
        } catch (IllegalStateException e) {
            throw FlowProblems.toolsUnavailable("The organization's tool servers could not be read: " + e.getMessage());
        }
        List<Unavailable> unavailable = new ArrayList<>();
        for (TenantFlowSection.McpServer server : registered.values()) {
            StreamableMcpClient client = external.open(server);
            try {
                for (LlmToolDef tool : client.listTools()) {
                    tools.add(new Tool(server.name(), tool.name(), tool.description(), tool.readOnly(), tool.destructive(), true));
                }
            } catch (McpException e) {
                unavailable.add(new Unavailable(server.name(), e.getMessage()));
            } finally {
                client.close();
            }
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("tools", tools);
        answer.put("unavailable", unavailable);
        return answer;
    }
}
