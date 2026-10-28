// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.llm.LlmToolDef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Minimal MCP client for one of the platform's tool servers.
 *
 * <p>datahub-api and datahub-analysis both run Spring AI's {@code STATELESS} protocol mode, which
 * exposes exactly one route — {@code POST /mcp} — and needs no {@code initialize} handshake and no
 * session id. That makes the whole client surface two JSON-RPC methods, which is why this is
 * hand-written rather than pulling in an MCP client library and its reactive stack. The wire
 * itself is {@link McpRpc}, shared with the client for external servers.
 *
 * <p><strong>Policy-free.</strong> It forwards whatever name it is given (an unadvertised one
 * reaches the server's "unknown tool" error) and reports what the server said about each tool:
 * the {@code readOnlyHint}/{@code destructiveHint} annotations land on {@link LlmToolDef}, and a
 * caller that must not write refuses on those, never on a name.
 *
 * <h3>Authentication</h3>
 * The bearer token is a <strong>method parameter on every call, never a field</strong>. Each
 * request carries the caller's own JWT so that per-dataset ACLs and {@code TenantContext} tenant
 * routing apply exactly as they do to the REST API — both servers validate the same issuer, and
 * datahub-analysis forwards the token to the api for its data gathering. Holding a token on the
 * instance would silently turn this into a shared service account and bypass both.
 */
public final class StatelessMcpClient {

    private final McpRpc rpc;

    /**
     * @param name    for log lines and errors: "datahub-api", "datahub-analysis"
     * @param baseUrl the server's base URL; {@code /mcp} is appended here
     * @param http    shared by the caller across servers and users, so the connection pool is
     */
    public StatelessMcpClient(String name, String baseUrl, HttpClient http, JsonMapper json) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') end--;
        this.rpc = new McpRpc(name, URI.create(baseUrl.substring(0, end) + "/mcp"), http, json, Duration.ofSeconds(60));
    }

    public String name() {
        return rpc.name();
    }

    public URI endpoint() {
        return rpc.endpoint();
    }

    /**
     * Every tool the server advertises, with its hints.
     *
     * @throws McpException if the server did not answer with a tool list
     */
    public List<LlmToolDef> listTools(String bearer) {
        return rpc.tools(call(bearer, "tools/list", Map.of()));
    }

    /**
     * Invoke one tool as the user the bearer belongs to.
     *
     * <p>A tool that ran and failed is a result with {@link McpToolResult#isError()}; only a
     * request that produced no result at all throws.
     *
     * @throws McpException on a transport failure, a non-200 status, or a JSON-RPC error
     */
    public McpToolResult callTool(String bearer, String tool, Map<String, Object> arguments) {
        return McpRpc.toolResult(call(bearer, "tools/call", Map.of("name", tool, "arguments", arguments)));
    }

    /**
     * This server as a run calls it: the token is asked for on every call and never held, so a
     * refreshed one is used the moment it exists and a lost lease stops the calls with it.
     */
    public McpTools as(Supplier<String> bearer) {
        return new McpTools() {
            @Override
            public String name() {
                return rpc.name();
            }

            @Override
            public List<LlmToolDef> listTools() {
                return StatelessMcpClient.this.listTools(bearer.get());
            }

            @Override
            public McpToolResult callTool(String tool, Map<String, Object> arguments) {
                return StatelessMcpClient.this.callTool(bearer.get(), tool, arguments);
            }
        };
    }

    /** One JSON-RPC round trip as the bearer. Returns the {@code result} node. */
    private JsonNode call(String bearer, String method, Object params) {
        long id = rpc.nextId();
        return rpc.result(rpc.post(Map.of("Authorization", "Bearer " + bearer), McpRpc.request(id, method, params)), id);
    }
}
