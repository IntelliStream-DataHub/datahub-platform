// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.agents;

import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.llm.LlmToolDef;
import ai.intellistream.datahub.llm.mcp.McpException;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tools an agent may be given: what the platform's MCP servers advertise, with their hints, as
 * the caller. Asked with the caller's own token, so the list is what that person could call; the
 * agent then runs as its deployed identity, whose access may differ, and a tool that identity may
 * not call fails in the run, as any call does.
 */
@RestController
public class AgentToolsController {

    /** One tool as the picker shows it. {@code readOnly} and {@code destructive} are the server's hints, null when it gave none. */
    public record Tool(String server, String name, String description, Boolean readOnly, Boolean destructive) {
    }

    private final FlowService flows;
    private final List<StatelessMcpClient> servers;

    public AgentToolsController(FlowService flows, List<StatelessMcpClient> mcpServers) {
        this.flows = flows;
        this.servers = mcpServers;
    }

    /**
     * {@code {"tools": [...]}}, server by server in the order a run looks a name up. Takes
     * {@code /flows/read}; 503 when a server does not answer.
     */
    @GetMapping("/agents/tools")
    public Map<String, List<Tool>> tools() {
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
                tools.add(new Tool(server.name(), tool.name(), tool.description(), tool.readOnly(), tool.destructive()));
            }
        }
        return Map.of("tools", tools);
    }
}
