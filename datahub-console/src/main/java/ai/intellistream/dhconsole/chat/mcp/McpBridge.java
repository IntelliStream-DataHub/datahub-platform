// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.mcp;

import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client for the platform's tool servers.
 *
 * <p>There are two: datahub-api (entity CRUD + graph tools) and datahub-analysis (the timeseries
 * relationship analysis). Both run Spring AI's {@code STATELESS} protocol mode, which exposes
 * exactly one route — {@code POST /mcp} — and needs no {@code initialize} handshake and no session
 * id. That makes the whole client surface two JSON-RPC methods, which is why this is hand-written
 * rather than pulling in an MCP client library and its reactive stack.
 *
 * <p>Tool names route to the server that advertised them (learned from {@code tools/list}, never
 * from a name prefix). A server that cannot be reached degrades to "its tools are absent this
 * turn": the chat keeps working against the remaining servers — deployments without
 * datahub-analysis simply never see its tools.
 *
 * <h3>Authentication</h3>
 * The bearer token is a <strong>method parameter on every call, never a field</strong>. Each
 * request must carry the signed-in console user's own JWT so that per-dataset ACLs and
 * {@code TenantContext} tenant routing apply to the chat exactly as they do to the REST UI —
 * both servers validate the same issuer, and datahub-analysis forwards the token to the api for
 * its data gathering. Holding a token on the instance would silently turn this into a shared
 * service account and bypass both. Get the token from {@code AccessTokens.token()} on the
 * request thread.
 *
 * @see <a href="file:../../../../../../../../datahub-api/src/main/java/ai/intellistream/datahub/api/mcp/McpConfig.java">McpConfig</a>
 * @see <a href="file:../../../../../../../../datahub-analysis/src/main/java/ai/intellistream/datahub/analysis/mcp/AnalysisMcpConfig.java">AnalysisMcpConfig</a>
 */
@Slf4j
@Component
public class McpBridge {

    /**
     * {@code WebMvcStatelessServerTransport} rejects the request with an empty 400 unless the
     * Accept header contains both of these media types. It compares with {@code MediaType.equals},
     * so quality parameters ({@code ;q=0.9}) break the match — this string must stay exact.
     * The response itself is plain {@code application/json}; nothing here parses SSE.
     */
    static final String ACCEPT = "application/json, text/event-stream";

    /** The advertised tool lists are derived from annotations at build time — same for every tenant. */
    private static final Duration TOOL_CACHE_TTL = Duration.ofHours(1);

    private final HttpClient http;
    private final JsonMapper json;
    private final List<Server> servers;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<String, CachedTools> toolCache = new ConcurrentHashMap<>();
    /** toolName → the server that advertised it. Tool sets are static per build, so no eviction. */
    private final Map<String, Server> routes = new ConcurrentHashMap<>();

    /**
     * Single constructor on purpose: Spring cannot choose between several without {@code @Autowired}
     * and falls back to a no-arg constructor that does not exist. Tests point this at local
     * servers by passing their base URLs, which also exercises the endpoint-building above.
     *
     * @param analysisUrl same property the Analyze tab posts to ({@code layout/main.html}); blank
     *                    disables the analysis server rather than probing a dead endpoint each turn
     */
    public McpBridge(JsonMapper json,
                     @Value("${datahub.url:http://localhost:8081}") String datahubUrl,
                     @Value("${datahub.analysis.url:http://localhost:8082}") String analysisUrl) {
        this.json = json;
        List<Server> configured = new ArrayList<>();
        configured.add(new Server("datahub-api", mcpEndpoint(datahubUrl)));
        if (analysisUrl != null && !analysisUrl.isBlank()) {
            configured.add(new Server("datahub-analysis", mcpEndpoint(analysisUrl)));
        }
        this.servers = List.copyOf(configured);
        // One shared client so the connection pool is reused across users and turns, matching
        // datahub-analysis's AnalysisApiClientFactory.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static URI mcpEndpoint(String baseUrl) {
        return URI.create(baseUrl.replaceAll("/+$", "") + "/mcp");
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
        for (Server server : servers) {
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

    private List<LlmToolDef> toolsFor(Server server, String bearer) {
        CachedTools cached = toolCache.get(server.name());
        if (cached != null && cached.isFresh()) {
            return cached.tools();
        }
        JsonNode result;
        try {
            result = rpc(server, bearer, "tools/list", Map.of());
        } catch (McpException e) {
            if (e.isUnauthorized()) {
                throw e;
            }
            log.warn("MCP server {} did not answer tools/list ({}); its tools are unavailable this turn",
                    server.name(), e.getMessage());
            // Expired-but-present beats absent: the tool set only changes on a redeploy.
            return cached != null ? cached.tools() : List.of();
        }
        List<LlmToolDef> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools").values()) {
            tools.add(new LlmToolDef(
                    tool.path("name").asString(),
                    tool.path("description").asString(),
                    // Kept as a raw JSON string: both provider adapters pass JSON Schema through
                    // untouched, so there is nothing to model here.
                    json.writeValueAsString(tool.path("inputSchema"))));
        }
        toolCache.put(server.name(), new CachedTools(List.copyOf(tools), Instant.now().plus(TOOL_CACHE_TTL)));
        log.debug("Loaded {} MCP tools from {} at {}", tools.size(), server.name(), server.endpoint());
        return List.copyOf(tools);
    }

    /**
     * Invoke one tool as the user the bearer belongs to, on the server that advertised it.
     *
     * <p>Tool failures are returned, not thrown — see {@link McpToolResult}. Only a transport
     * failure raises {@link McpException}.
     */
    public McpToolResult callTool(String bearer, String name, Map<String, Object> arguments) {
        Server server = serverFor(name, bearer);
        JsonNode result;
        try {
            result = rpc(server, bearer, "tools/call", Map.of("name", name, "arguments", arguments));
        } catch (McpException e) {
            if (e.isUnauthorized()) {
                throw e; // the caller must re-authenticate; the model can do nothing with this
            }
            log.warn("MCP call to {} on {} failed: {}", name, server.name(), e.getMessage());
            return McpToolResult.error("The tool call failed: " + e.getMessage());
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content").values()) {
            if ("text".equals(block.path("type").asString())) {
                text.append(block.path("text").asString());
            }
        }
        return new McpToolResult(text.toString(), result.path("isError").asBoolean(false));
    }

    /**
     * The server that advertised {@code toolName}. A cold route (a call before any
     * {@code tools/list} this process) refreshes the lists once; a name no server claims falls
     * back to the primary, whose JSON-RPC "unknown tool" error then reaches the model as an
     * ordinary tool failure it can adapt to.
     */
    private Server serverFor(String toolName, String bearer) {
        Server server = routes.get(toolName);
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

    /**
     * One JSON-RPC round trip. Returns the {@code result} node.
     *
     * @throws McpException on a transport failure, a non-200 status, or a JSON-RPC {@code error}
     */
    private JsonNode rpc(Server server, String bearer, String method, Object params) {
        String body = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", requestIds.incrementAndGet(),
                "method", method,
                "params", params));

        HttpRequest request = HttpRequest.newBuilder(server.endpoint())
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted calling " + method, e);
        } catch (Exception e) {
            throw new McpException("Could not reach " + server.name() + " at " + server.endpoint(), e);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new McpException("Not authorised to call " + server.name() + " (" + response.statusCode() + ")", true);
        }
        if (response.statusCode() != 200) {
            // A 400 with an empty body here almost always means the Accept header was wrong.
            throw new McpException("MCP " + method + " returned HTTP " + response.statusCode()
                    + (response.body().isBlank() ? "" : ": " + response.body()), false);
        }

        JsonNode envelope = json.readTree(response.body());
        JsonNode error = envelope.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpException(error.path("message").asString("unknown JSON-RPC error"), false);
        }
        return envelope.path("result");
    }

    private record Server(String name, URI endpoint) {
    }

    private record CachedTools(List<LlmToolDef> tools, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
