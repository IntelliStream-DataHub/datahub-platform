// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.llm.LlmToolDef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client for one of the platform's tool servers.
 *
 * <p>datahub-api and datahub-analysis both run Spring AI's {@code STATELESS} protocol mode, which
 * exposes exactly one route — {@code POST /mcp} — and needs no {@code initialize} handshake and no
 * session id. That makes the whole client surface two JSON-RPC methods, which is why this is
 * hand-written rather than pulling in an MCP client library and its reactive stack.
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

    /**
     * {@code WebMvcStatelessServerTransport} rejects the request with an empty 400 unless the
     * Accept header contains both of these media types. It compares with {@code MediaType.equals},
     * so quality parameters ({@code ;q=0.9}) break the match — this string must stay exact.
     * The response itself is plain {@code application/json}; nothing here parses SSE.
     */
    private static final String ACCEPT = "application/json, text/event-stream";

    private final String name;
    private final URI endpoint;
    private final HttpClient http;
    private final JsonMapper json;
    private final AtomicLong requestIds = new AtomicLong();

    /**
     * @param name    for log lines and errors: "datahub-api", "datahub-analysis"
     * @param baseUrl the server's base URL; {@code /mcp} is appended here
     * @param http    shared by the caller across servers and users, so the connection pool is
     */
    public StatelessMcpClient(String name, String baseUrl, HttpClient http, JsonMapper json) {
        this.name = name;
        this.http = http;
        this.json = json;
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') end--;
        this.endpoint = URI.create(baseUrl.substring(0, end) + "/mcp");
    }

    public String name() {
        return name;
    }

    public URI endpoint() {
        return endpoint;
    }

    /**
     * Every tool the server advertises, with its hints.
     *
     * @throws McpException if the server did not answer with a tool list
     */
    public List<LlmToolDef> listTools(String bearer) {
        JsonNode result = rpc(bearer, "tools/list", Map.of());
        List<LlmToolDef> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools").values()) {
            JsonNode annotations = tool.path("annotations");
            tools.add(new LlmToolDef(
                    tool.path("name").asString(),
                    tool.path("description").asString(),
                    // Kept as a raw JSON string: both provider adapters pass JSON Schema through
                    // untouched, so there is nothing to model here.
                    json.writeValueAsString(tool.path("inputSchema")),
                    hint(annotations, "readOnlyHint"),
                    hint(annotations, "destructiveHint")));
        }
        return List.copyOf(tools);
    }

    /** The hint as the server stated it, or null: an absent hint is not a false one. */
    private static Boolean hint(JsonNode annotations, String field) {
        JsonNode value = annotations.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
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
        JsonNode result = rpc(bearer, "tools/call", Map.of("name", tool, "arguments", arguments));
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content").values()) {
            if ("text".equals(block.path("type").asString())) {
                text.append(block.path("text").asString());
            }
        }
        return new McpToolResult(text.toString(), result.path("isError").asBoolean(false));
    }

    /** One JSON-RPC round trip. Returns the {@code result} node. */
    private JsonNode rpc(String bearer, String method, Object params) {
        String body = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", requestIds.incrementAndGet(),
                "method", method,
                "params", params));

        HttpRequest request = HttpRequest.newBuilder(endpoint)
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
            throw new McpException("Could not reach " + name + " at " + endpoint, e);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new McpException("Not authorised to call " + name + " (" + response.statusCode() + ")", true);
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
}
