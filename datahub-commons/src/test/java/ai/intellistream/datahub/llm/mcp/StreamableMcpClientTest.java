// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.llm.LlmToolDef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Streamable HTTP wire against a loopback server that behaves as the spec's servers do:
 * a handshake, a session id, event-stream replies, and a session it may forget.
 */
class StreamableMcpClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Server server;

    @BeforeEach
    void start() throws IOException {
        server = new Server();
        server.tool("ticket_get", true);
        server.tool("ticket_close", false);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void handshakesOnceThenCarriesTheSessionAndVersionWithTheConfiguredHeader() {
        StreamableMcpClient client = client("X-Api-Key", "k-1", Duration.ofSeconds(5), true);

        List<LlmToolDef> tools = client.listTools();
        client.callTool("crm__ticket_get", Map.of("id", 7));

        // One session for both calls; the stream's notification before the answer was passed over.
        assertThat(tools).extracting(LlmToolDef::name).containsExactly("crm__ticket_get", "crm__ticket_close");
        assertThat(tools.get(0).readOnly()).isTrue();
        assertThat(tools.get(1).readOnly()).isFalse();
        assertThat(server.recorded).extracting(Recorded::method)
                .containsExactly("initialize", "notifications/initialized", "tools/list", "tools/call");
        Recorded initialize = server.recorded.get(0);
        assertThat(initialize.headers.get("x-api-key")).containsExactly("k-1");
        assertThat(initialize.headers).doesNotContainKeys("authorization", "mcp-session-id", "mcp-protocol-version");
        assertThat(initialize.body).contains("\"protocolVersion\":\"2025-06-18\"").contains("\"clientInfo\"");
        for (Recorded later : server.recorded.subList(1, 4)) {
            assertThat(later.headers.get("mcp-session-id")).containsExactly("s-1");
            // The version the server settled on, not the one offered.
            assertThat(later.headers.get("mcp-protocol-version")).containsExactly("2025-03-26");
            assertThat(later.headers.get("x-api-key")).containsExactly("k-1");
            assertThat(later.headers).doesNotContainKey("authorization");
        }
        assertThat(server.recorded.get(3).body).contains("\"name\":\"ticket_get\"").contains("\"id\":7");
    }

    @Test
    void followsNextCursorAndLeavesOutNamesThatWouldNotFit() {
        server.tool("a_name_of_thirty_nine_characters_xxxxxx", true);
        server.pageSize = 2;

        List<LlmToolDef> tools = client("Authorization", "Bearer t", Duration.ofSeconds(5), true).listTools();

        assertThat(tools).extracting(LlmToolDef::name).containsExactly("crm__ticket_get", "crm__ticket_close");
        assertThat(server.recorded).extracting(Recorded::method)
                .containsExactly("initialize", "notifications/initialized", "tools/list", "tools/list");
        assertThat(server.recorded.get(3).body).contains("\"cursor\":\"2\"");
    }

    @Test
    void callToolStripsThePrefixAndRefusesAnotherServersName() {
        StreamableMcpClient client = client("Authorization", "Bearer t", Duration.ofSeconds(5), true);

        McpToolResult result = client.callTool("crm__ticket_close", Map.of());

        assertThat(result.text()).isEqualTo("ran ticket_close");
        assertThat(result.isError()).isFalse();
        assertThatThrownBy(() -> client.callTool("erp__ticket_close", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aForgottenSessionIsReplacedOnceAndTheCallRepeated() {
        StreamableMcpClient client = client("Authorization", "Bearer t", Duration.ofSeconds(5), true);
        client.listTools();
        server.forgetSession();

        McpToolResult result = client.callTool("crm__ticket_get", Map.of());

        assertThat(result.text()).isEqualTo("ran ticket_get");
        assertThat(server.recorded).extracting(Recorded::method).containsExactly(
                "initialize", "notifications/initialized", "tools/list",
                "tools/call", "initialize", "notifications/initialized", "tools/call");
        assertThat(server.recorded.getLast().headers.get("mcp-session-id")).containsExactly("s-2");
    }

    @Test
    void aRefusedCredentialAndARedirectAreFailuresOfTheirOwn() {
        server.answer = 401;
        assertThatThrownBy(() -> client("Authorization", "Bearer bad", Duration.ofSeconds(5), true).listTools())
                .isInstanceOf(McpException.class)
                .hasMessageContaining("refused the credential")
                .satisfies(e -> assertThat(((McpException) e).isUnauthorized()).isTrue());

        server.answer = 302;
        assertThatThrownBy(() -> client("Authorization", "Bearer t", Duration.ofSeconds(5), true).listTools())
                .isInstanceOf(McpException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    void aReplyOverTheCapIsAFailureNotAHeapProblem() {
        server.padBytes = McpRpc.MAX_BODY + 10;

        assertThatThrownBy(() -> client("Authorization", "Bearer t", Duration.ofSeconds(10), true).listTools())
                .isInstanceOf(McpException.class)
                .satisfies(e -> assertThat(((McpException) e).isUnauthorized()).isFalse());
    }

    @Test
    void aStreamHeldOpenFailsWithinTheTimeout() {
        server.holdMillis = 4_000;
        long started = System.nanoTime();

        assertThatThrownBy(() -> client("Authorization", "Bearer t", Duration.ofSeconds(1), true).listTools())
                .isInstanceOf(McpException.class)
                .hasMessageContaining("did not answer within 1 s");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void closeEndsTheSessionAndShrugsAtTheServersAnswer() {
        server.deleteStatus = 405;
        StreamableMcpClient client = client("Authorization", "Bearer t", Duration.ofSeconds(5), true);
        client.listTools();

        client.close();
        client.close();

        assertThat(server.deletes).isEqualTo(1);
        assertThat(server.recorded.getLast().method).isEqualTo("DELETE");
        assertThat(server.recorded.getLast().headers.get("mcp-session-id")).containsExactly("s-1");
    }

    @Test
    void withoutASessionThereIsNothingToClose() {
        server.issueSession = false;
        StreamableMcpClient client = client("Authorization", "Bearer t", Duration.ofSeconds(5), true);
        client.listTools();

        client.close();

        assertThat(server.deletes).isZero();
        assertThat(server.recorded.get(2).headers).doesNotContainKey("mcp-session-id");
    }

    @Test
    void aPrivateUrlIsRefusedBeforeAnyRequestUnlessTheDeploymentAllowsIt() {
        assertThatThrownBy(() -> client("Authorization", "Bearer t", Duration.ofSeconds(5), false).listTools())
                .isInstanceOf(McpException.class)
                .hasMessageContaining("private or local address");
        assertThat(server.recorded).isEmpty();
    }

    private StreamableMcpClient client(String headerName, String headerValue, Duration timeout, boolean allowPrivate) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return new StreamableMcpClient("crm", server.url(), headerName, headerValue, http, JSON, timeout, allowPrivate);
    }

    // ---- the server -----------------------------------------------------------------------------

    private record Recorded(String method, Map<String, List<String>> headers, String body) {
    }

    /** Streamable HTTP on loopback: tools/list answers as an event stream, tools/call as JSON. */
    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final List<Recorded> recorded = new ArrayList<>();
        final List<Map<String, Object>> tools = new ArrayList<>();
        boolean issueSession = true;
        int pageSize;
        int answer = 200;
        int deleteStatus = 204;
        int deletes;
        long padBytes;
        long holdMillis;
        private int sessions;
        private String liveSession;

        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/mcp", this::handle);
            http.start();
        }

        String url() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
        }

        void tool(String name, boolean readOnly) {
            tools.add(Map.of("name", name, "description", name.replace('_', ' '), "inputSchema", Map.of("type", "object"),
                    "annotations", Map.of("readOnlyHint", readOnly)));
        }

        void forgetSession() {
            liveSession = null;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v));
            if ("DELETE".equals(exchange.getRequestMethod())) {
                recorded.add(new Recorded("DELETE", headers, body));
                deletes++;
                exchange.sendResponseHeaders(deleteStatus, -1);
                exchange.close();
                return;
            }
            JsonNode request = JSON.readTree(body);
            String method = request.path("method").asString();
            recorded.add(new Recorded(method, headers, body));
            if (answer != 200) {
                exchange.sendResponseHeaders(answer, -1);
                exchange.close();
                return;
            }
            if ("initialize".equals(method)) {
                if (issueSession) {
                    liveSession = "s-" + (++sessions);
                    exchange.getResponseHeaders().add("Mcp-Session-Id", liveSession);
                }
                json(exchange, Map.of("jsonrpc", "2.0", "id", request.path("id").asLong(), "result",
                        Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(), "serverInfo", Map.of("name", "fake"))));
                return;
            }
            if (issueSession && (liveSession == null || !liveSession.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")))) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if ("tools/list".equals(method)) {
                int from = request.path("params").path("cursor").asInt(0);
                int to = pageSize > 0 ? Math.min(from + pageSize, tools.size()) : tools.size();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("tools", tools.subList(from, to));
                if (to < tools.size()) {
                    result.put("nextCursor", Integer.toString(to));
                }
                events(exchange, Map.of("jsonrpc", "2.0", "id", request.path("id").asLong(), "result", result));
                return;
            }
            String name = request.path("params").path("name").asString();
            json(exchange, Map.of("jsonrpc", "2.0", "id", request.path("id").asLong(), "result",
                    Map.of("content", List.of(Map.of("type", "text", "text", "ran " + name)))));
        }

        private static void json(HttpExchange exchange, Object message) throws IOException {
            byte[] out = JSON.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        }

        /** A keep-alive comment, a notification nobody asked for, then the answer; held open on request. */
        private void events(HttpExchange exchange, Object message) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write((": keep-alive" + (padBytes > 0 ? " " + "x".repeat((int) padBytes) : "") + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                        + "\"params\":{\"level\":\"info\",\"data\":\"hello\"}}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (holdMillis > 0) {
                    try {
                        Thread.sleep(holdMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                out.write(("event: message\ndata: " + JSON.writeValueAsString(message) + "\n\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }
}
