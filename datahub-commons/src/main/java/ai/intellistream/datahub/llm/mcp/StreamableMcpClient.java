// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.helpers.utils.OutboundUrls;
import ai.intellistream.datahub.llm.LlmToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An external MCP server over the spec's Streamable HTTP transport, as a tenant registered it: the
 * endpoint it gave, called with the one header it gave.
 *
 * <p>One instance is one session with one credential: it does the {@code initialize} handshake
 * on first use, keeps the {@code Mcp-Session-Id} the server may issue and the protocol version
 * it settled on, starts over once when the server has forgotten the session (a 404), and ends the
 * session on {@link #close()}. Open one for a run or a request and close it after; it is not for
 * sharing between threads.
 *
 * <p><strong>The tools are advertised as {@code <name>__<tool>}</strong>, so that an agent's tool
 * list and its transcript say where a tool lives and two servers' tools never collide; a name
 * that would not fit a model provider's 64-character limit is left out and logged. The server's
 * descriptions and results reach the model as they are: a registered server is trusted as much as
 * the tenant that registered it, and no more — the URL is checked against private networks right
 * before the first connection, redirects are not followed (the {@code HttpClient} is the
 * caller's; build it with {@code followRedirects(NEVER)}), and replies are bounded in time and
 * size.
 *
 * <p>Not supported: the server's own requests back to the client (sampling, elicitation), which
 * are passed over in the stream; a server that needs them answered cannot be used.
 */
public final class StreamableMcpClient implements McpTools {

    private static final Logger log = LoggerFactory.getLogger(StreamableMcpClient.class);

    static final String PROTOCOL_VERSION = "2025-06-18";
    /** What of a tool name fits after a 24-character server name and {@code __} within 64. */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,38}");
    private static final int MAX_PAGES = 20;

    private final String name;
    private final String headerName;
    private final String headerValue;
    private final boolean allowPrivateNetworks;
    private final McpRpc rpc;
    private boolean initialized;
    private String protocolVersion;
    private String sessionId;

    /**
     * @param name        the registered name, and the prefix of every tool
     * @param url         the endpoint as registered; nothing is appended
     * @param headerName  the header the credential travels in, {@code Authorization} as a rule
     * @param headerValue the credential, e.g. {@code Bearer …}
     * @param timeout     the most one exchange may take, reply included
     */
    public StreamableMcpClient(String name, String url, String headerName, String headerValue,
                               HttpClient http, JsonMapper json, Duration timeout, boolean allowPrivateNetworks) {
        this.name = name;
        this.headerName = headerName;
        this.headerValue = headerValue;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.rpc = new McpRpc(name, URI.create(url), http, json, timeout);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<LlmToolDef> listTools() {
        ensureInitialized();
        List<LlmToolDef> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode result = call("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
            for (LlmToolDef tool : rpc.tools(result)) {
                if (!TOOL_NAME.matcher(tool.name()).matches()) {
                    log.info("{}: the tool \"{}\" is not offered; its name does not fit 38 characters of letters, digits, _ and -",
                            name, tool.name());
                    continue;
                }
                tools.add(new LlmToolDef(name + "__" + tool.name(), tool.description(), tool.inputSchemaJson(),
                        tool.readOnly(), tool.destructive()));
            }
            cursor = result.path("nextCursor").asString(null);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return List.copyOf(tools);
    }

    @Override
    public McpToolResult callTool(String tool, Map<String, Object> arguments) {
        String prefix = name + "__";
        if (!tool.startsWith(prefix)) {
            throw new IllegalArgumentException(tool + " is not a tool of " + name);
        }
        ensureInitialized();
        return McpRpc.toolResult(call("tools/call", Map.of("name", tool.substring(prefix.length()), "arguments", arguments)));
    }

    /** Tells the server the session is over, when it issued one. Idempotent; the server's answer does not matter. */
    @Override
    public void close() {
        if (sessionId == null) {
            initialized = false;
            return;
        }
        try {
            rpc.delete(headers());
        } catch (RuntimeException e) {
            log.debug("{}: could not end the session: {}", name, e.getMessage());
        } finally {
            sessionId = null;
            protocolVersion = null;
            initialized = false;
        }
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        // Checked here, right before the first connection, as well as when the URL was registered.
        String refusal = OutboundUrls.refuse(rpc.endpoint(), allowPrivateNetworks);
        if (refusal != null) {
            throw new McpException(name + " may not be called: " + refusal, false);
        }
        initialize();
    }

    /** The handshake: {@code initialize}, then {@code notifications/initialized}; the session and version are the server's. */
    private void initialize() {
        initialized = false;
        sessionId = null;
        protocolVersion = null;
        long id = rpc.nextId();
        McpRpc.Reply reply = rpc.post(headers(), McpRpc.request(id, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "intellistream-flow", "version", "1"))));
        JsonNode result = rpc.result(reply, id);
        protocolVersion = result.path("protocolVersion").asString(PROTOCOL_VERSION);
        sessionId = reply.headers().firstValue("Mcp-Session-Id").orElse(null);
        McpRpc.Reply acknowledged = rpc.post(headers(), McpRpc.notification("notifications/initialized"));
        if (acknowledged.status() < 200 || acknowledged.status() > 299) {
            throw rpc.failure(acknowledged);
        }
        initialized = true;
    }

    private JsonNode call(String method, Object params) {
        long id = rpc.nextId();
        McpRpc.Reply reply = rpc.post(headers(), McpRpc.request(id, method, params));
        if (reply.status() == 404 && sessionId != null) {
            // The server no longer knows the session: start another and ask once more.
            log.info("{}: the session was gone; starting a new one", name);
            initialize();
            id = rpc.nextId();
            reply = rpc.post(headers(), McpRpc.request(id, method, params));
        }
        return rpc.result(reply, id);
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(headerName, headerValue);
        if (protocolVersion != null) {
            headers.put("MCP-Protocol-Version", protocolVersion);
        }
        if (sessionId != null) {
            headers.put("Mcp-Session-Id", sessionId);
        }
        return headers;
    }
}
