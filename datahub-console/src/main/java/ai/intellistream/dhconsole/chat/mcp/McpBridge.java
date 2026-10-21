// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.mcp;

import ai.intellistream.datahub.llm.LlmToolDef;
import ai.intellistream.datahub.llm.mcp.McpException;
import ai.intellistream.datahub.llm.mcp.McpToolResult;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chat's view of the platform's tool servers: datahub-api (entity CRUD + graph tools) and
 * datahub-analysis (the timeseries relationship analysis), each spoken to through a
 * {@link StatelessMcpClient}.
 *
 * <p>Tool names route to the server that advertised them (learned from {@code tools/list}, never
 * from a name prefix). A server that cannot be reached degrades to "its tools are absent this
 * turn": the chat keeps working against the remaining servers — deployments without
 * datahub-analysis simply never see its tools.
 *
 * <h3>Authentication</h3>
 * The bearer token is a <strong>method parameter on every call, never a field</strong>, for the
 * reason the client gives: every request is made as the signed-in console user. Get the token
 * from {@code AccessTokens.token()} on the request thread.
 *
 * @see <a href="file:../../../../../../../../datahub-api/src/main/java/ai/intellistream/datahub/api/mcp/McpConfig.java">McpConfig</a>
 * @see <a href="file:../../../../../../../../datahub-analysis/src/main/java/ai/intellistream/datahub/analysis/mcp/AnalysisMcpConfig.java">AnalysisMcpConfig</a>
 */
@Slf4j
@Component
public class McpBridge {

    /** The advertised tool lists are derived from annotations at build time — same for every tenant. */
    private static final Duration TOOL_CACHE_TTL = Duration.ofHours(1);

    private final List<StatelessMcpClient> servers;
    private final Map<String, CachedTools> toolCache = new ConcurrentHashMap<>();
    /** toolName → the server that advertised it. Tool sets are static per build, so no eviction. */
    private final Map<String, StatelessMcpClient> routes = new ConcurrentHashMap<>();

    /**
     * Single constructor on purpose: Spring cannot choose between several without {@code @Autowired}
     * and falls back to a no-arg constructor that does not exist. Tests point this at local
     * servers by passing their base URLs.
     *
     * @param analysisUrl same property the Analyze tab posts to ({@code layout/main.html}); blank
     *                    disables the analysis server rather than probing a dead endpoint each turn
     */
    public McpBridge(JsonMapper json,
                     @Value("${datahub.url:http://localhost:8081}") String datahubUrl,
                     @Value("${datahub.analysis.url:http://localhost:8082}") String analysisUrl) {
        // One shared client so the connection pool is reused across users and turns, matching
        // datahub-analysis's AnalysisApiClientFactory.
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        List<StatelessMcpClient> configured = new ArrayList<>();
        configured.add(new StatelessMcpClient("datahub-api", datahubUrl, http, json));
        if (analysisUrl != null && !analysisUrl.isBlank()) {
            configured.add(new StatelessMcpClient("datahub-analysis", analysisUrl, http, json));
        }
        this.servers = List.copyOf(configured);
    }

    /**
     * Every tool the reachable servers advertise, first-server-first (datahub-api before
     * datahub-analysis). Cached process-wide per server for {@link #TOOL_CACHE_TTL}; the supplied
     * token is only used to populate cold caches. A server that fails to answer contributes its
     * last known tools if it ever answered, and nothing otherwise — never an exception, so one
     * missing service cannot take the chat down. Only a 401/403 propagates: the caller must
     * re-authenticate, and no server would accept the token anyway.
     */
    public List<LlmToolDef> listTools(String bearer) {
        List<LlmToolDef> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StatelessMcpClient server : servers) {
            for (LlmToolDef tool : toolsFor(server, bearer)) {
                if (seen.add(tool.name())) {
                    all.add(tool);
                    routes.put(tool.name(), server);
                } else {
                    log.warn("Tool {} advertised by more than one MCP server; keeping the first", tool.name());
                }
            }
        }
        return List.copyOf(all);
    }

    private List<LlmToolDef> toolsFor(StatelessMcpClient server, String bearer) {
        CachedTools cached = toolCache.get(server.name());
        if (cached != null && cached.isFresh()) {
            return cached.tools();
        }
        List<LlmToolDef> tools;
        try {
            tools = server.listTools(bearer);
        } catch (McpException e) {
            if (e.isUnauthorized()) {
                throw e;
            }
            log.warn("MCP server {} did not answer tools/list ({}); its tools are unavailable this turn",
                    server.name(), e.getMessage());
            // Expired-but-present beats absent: the tool set only changes on a redeploy.
            return cached != null ? cached.tools() : List.of();
        }
        toolCache.put(server.name(), new CachedTools(tools, Instant.now().plus(TOOL_CACHE_TTL)));
        log.debug("Loaded {} MCP tools from {} at {}", tools.size(), server.name(), server.endpoint());
        return tools;
    }

    /**
     * Invoke one tool as the user the bearer belongs to, on the server that advertised it.
     *
     * <p>Tool failures are returned, not thrown — see {@link McpToolResult}. Only an expired or
     * refused token raises {@link McpException}: the model can do nothing with it, and the user
     * has to sign in again.
     */
    public McpToolResult callTool(String bearer, String name, Map<String, Object> arguments) {
        StatelessMcpClient server = serverFor(name, bearer);
        try {
            return server.callTool(bearer, name, arguments);
        } catch (McpException e) {
            if (e.isUnauthorized()) {
                throw e;
            }
            log.warn("MCP call to {} on {} failed: {}", name, server.name(), e.getMessage());
            return McpToolResult.error("The tool call failed: " + e.getMessage());
        }
    }

    /**
     * The server that advertised {@code toolName}. A cold route (a call before any
     * {@code tools/list} this process) refreshes the lists once; a name no server claims falls
     * back to the primary, whose JSON-RPC "unknown tool" error then reaches the model as an
     * ordinary tool failure it can adapt to.
     */
    private StatelessMcpClient serverFor(String toolName, String bearer) {
        StatelessMcpClient server = routes.get(toolName);
        if (server != null) {
            return server;
        }
        try {
            listTools(bearer);
        } catch (McpException e) {
            if (e.isUnauthorized()) {
                throw e;
            }
        }
        return routes.getOrDefault(toolName, servers.getFirst());
    }

    private record CachedTools(List<LlmToolDef> tools, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
