// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.fake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * An MCP server on loopback: a tool list with hints, and canned call results. Stateless as the
 * platform's own servers are, or, when asked, Streamable HTTP as a server a tenant registers: a
 * handshake, a session id, event-stream replies, and a DELETE that ends the session.
 */
public final class FakeMcp implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A tool call as it arrived: the bare tool name, its arguments, and the request headers (lowercase). */
    public record Call(String name, Map<String, Object> args, String bearer, Map<String, String> headers) {
    }

    public final String name;
    public final boolean streamable;
    public final List<Map<String, Object>> tools = new ArrayList<>();
    public final List<Call> calls = new ArrayList<>();
    /** How many of the next calls answer 429 with Retry-After: 0. */
    public int rateLimitNext;
    public int refused;
    public int listCalls;
    /** Streamable only: sessions started, and DELETEs that ended one. */
    public int sessions;
    public int deletes;
    private String session;
    private final HttpServer server;

    public FakeMcp(String name) throws IOException {
        this(name, false);
    }

    public FakeMcp(String name, boolean streamable) throws IOException {
        this.name = name;
        this.streamable = streamable;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    /** The base URL for the platform client, which appends {@code /mcp}; the endpoint itself when streamable. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + (streamable ? "/mcp" : "");
    }

    public void tool(String toolName, Boolean readOnly, Boolean destructive) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", toolName);
        tool.put("description", toolName.replace('_', ' '));
        tool.put("inputSchema", Map.of("type", "object"));
        if (readOnly != null || destructive != null) {
            Map<String, Object> annotations = new LinkedHashMap<>();
            if (readOnly != null) {
                annotations.put("readOnlyHint", readOnly);
            }
            if (destructive != null) {
                annotations.put("destructiveHint", destructive);
            }
            tool.put("annotations", annotations);
        }
        tools.add(tool);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if ("DELETE".equals(exchange.getRequestMethod())) {
            deletes++;
            session = null;
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String method = request.path("method").asString();
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.getFirst()));
        if (streamable) {
            if ("initialize".equals(method)) {
                session = "s-" + (++sessions);
                exchange.getResponseHeaders().add("Mcp-Session-Id", session);
                json(exchange, envelope(request, Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "serverInfo", Map.of("name", name))));
                return;
            }
            if (session == null || !session.equals(headers.get("mcp-session-id"))) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
        }
        Map<String, Object> result;
        if ("tools/list".equals(method)) {
            listCalls++;
            result = Map.of("tools", tools);
        } else {
            if (rateLimitNext > 0) {
                rateLimitNext--;
                refused++;
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            JsonNode params = request.path("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = JSON.convertValue(params.path("arguments"), Map.class);
            calls.add(new Call(params.path("name").asString(), args, headers.get("authorization"), headers));
            result = Map.of("content", List.of(Map.of("type", "text", "text", "{\"items\":[]}")));
        }
        Map<String, Object> envelope = envelope(request, result);
        if (streamable && "tools/list".equals(method)) {
            events(exchange, envelope);
        } else {
            json(exchange, envelope);
        }
    }

    private static Map<String, Object> envelope(JsonNode request, Object result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", request.path("id").asLong());
        envelope.put("result", result);
        return envelope;
    }

    private static void json(HttpExchange exchange, Object message) throws IOException {
        byte[] out = JSON.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** An event stream: a notification nobody asked for, then the answer, as a spec server may frame it. */
    private static void events(HttpExchange exchange, Object message) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                    + "\"params\":{\"level\":\"info\",\"data\":\"listing\"}}\n\n"
                    + "event: message\ndata: " + JSON.writeValueAsString(message) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
