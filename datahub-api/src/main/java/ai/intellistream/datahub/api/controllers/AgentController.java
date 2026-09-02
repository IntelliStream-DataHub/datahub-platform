// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.agent.AgentDefinition;
import ai.intellistream.datahub.agent.ToolCatalogEntry;
import ai.intellistream.datahub.api.mcp.ToolCatalog;
import ai.intellistream.datahub.api.services.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent definitions for the tenant the caller's token belongs to, and the catalogue of tools an
 * agent may be given.
 *
 * <p>Reading is open to any caller who may use the api at all — a client has to know which agents
 * exist before it can run one, and nothing here is a secret: an agent names a Vault backend, it
 * never carries the credential.
 *
 * <p>Writing requires an all-datasets write grant, enforced in {@code AgentService}, because an
 * agent's tool list governs what an assistant may reach across the whole tenant.
 */
@RestController
@RequestMapping("/agents")
@Tag(name = "Agents", description = """
        Named LLM assistants configured for your tenant: their instructions, which model backend
        they use, and exactly which MCP tools each may be offered. What an agent can actually
        reach is its tool list intersected with the permissions of whoever runs it — see
        GET /tenant/permissions.""")
public class AgentController {

    private final AgentService agentService;
    private final ToolCatalog toolCatalog;

    public AgentController(AgentService agentService, ToolCatalog toolCatalog) {
        this.agentService = agentService;
        this.toolCatalog = toolCatalog;
    }

    @Operation(summary = "List the agents configured for your tenant",
            description = """
                    Includes disabled agents, so a management UI can show them; a client about to
                    run one should skip any whose `enabled` is false.""")
    @GetMapping
    public ResponseEntity<List<AgentDefinition>> list() {
        return ResponseEntity.ok(agentService.list());
    }

    @Operation(summary = "List every MCP tool an agent may be given",
            description = """
                    The platform's tool catalogue: each tool's name, whether it reads or writes,
                    and which MCP server serves it. Use it to populate an agent's allowlist —
                    a name not in this list is rejected on write.

                    Tools are currently restricted to read-only ones; a write tool appears here
                    but cannot be added to an agent.""")
    @GetMapping("/tools")
    public ResponseEntity<List<ToolCatalogEntry>> tools() {
        return ResponseEntity.ok(toolCatalog.entries());
    }

    @Operation(summary = "Get one agent by name")
    @GetMapping("/{externalId}")
    public ResponseEntity<AgentDefinition> get(@PathVariable String externalId) {
        return ResponseEntity.ok(agentService.get(externalId));
    }

    @Operation(summary = "Create or replace an agent",
            description = """
                    Upserts the agent named in the path; the `externalId` in the body, if present,
                    is ignored in favour of it. Requires an all-datasets write grant.

                    Every tool in `toolAllowlist` must appear in GET /agents/tools and must be
                    read-only, otherwise the request is rejected — a tool name that matches
                    nothing would otherwise present as an assistant that quietly cannot do
                    something, with nothing saying why.""")
    @PutMapping("/{externalId}")
    public ResponseEntity<AgentDefinition> save(@PathVariable String externalId,
                                                @RequestBody AgentDefinition definition) {
        return ResponseEntity.ok(agentService.save(externalId, definition));
    }

    @Operation(summary = "Delete an agent",
            description = "Requires an all-datasets write grant. To keep the definition but stop "
                    + "offering it, set `enabled` to false instead.")
    @DeleteMapping("/{externalId}")
    public ResponseEntity<Void> delete(@PathVariable String externalId) {
        agentService.delete(externalId);
        return ResponseEntity.noContent().build();
    }
}
