// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.fake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A stateless MCP server on loopback: a tool list with hints, and canned call results. */
public final class FakeMcp implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Call(String name, Map<String, Object> args, String bearer) {
    }

    public final String name;
    public final List<Map<String, Object>> tools = new ArrayList<>();
    public final List<Call> calls = new ArrayList<>();
    /** How many of the next calls answer 429 with Retry-After: 0. */
    public int rateLimitNext;
    public int refused;
    public int listCalls;
    private final HttpServer server;

    public FakeMcp(String name) throws IOException {
        this.name = name;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
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
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String bearer = exchange.getRequestHeaders().getFirst("Authorization");
        Map<String, Object> result;
        if ("tools/list".equals(request.path("method").asString())) {
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
            calls.add(new Call(params.path("name").asString(), args, bearer));
            result = Map.of("content", List.of(Map.of("type", "text", "text", "{\"items\":[]}")));
        }
        byte[] out = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.path("id").asLong(),
                "result", result)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

