// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.llm.LlmToolDef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire, against a loopback server playing datahub-api (the repo convention for outbound HTTP:
 * no mocking framework between the code and the socket). Routing across servers and caching are
 * the console bridge's and tested there.
 */
class StatelessMcpClientTest {

    private static final String TOOLS_LIST_RESPONSE = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"dataset_list","description":"Browse datasets.",
               "inputSchema":{"type":"object","properties":{"limit":{"type":"integer"}}},
               "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true}},
              {"name":"dataset_delete","description":"Delete one dataset.",
               "inputSchema":{"type":"object","properties":{"id":{"type":"integer"}}},
               "annotations":{"readOnlyHint":false,"destructiveHint":true}},
              {"name":"something_old","description":"From a server that says nothing.",
               "inputSchema":{"type":"object"}}
            ]}}""";

    @Test
    void listToolsCarriesTheServersHintsAndNothingWhereItSaidNothing() throws Exception {
        withServer(200, TOOLS_LIST_RESPONSE, (client, recorded) -> {
            List<LlmToolDef> tools = client.listTools("tok-1");

            assertThat(tools).extracting(LlmToolDef::name)
                    .containsExactly("dataset_list", "dataset_delete", "something_old");
            LlmToolDef list = tools.get(0);
            assertThat(list.description()).isEqualTo("Browse datasets.");
            // The schema is passed through as raw JSON — both provider adapters send it verbatim.
            assertThat(list.inputSchemaJson()).contains("\"type\":\"object\"").contains("\"limit\"");
            assertThat(list.readOnly()).isTrue();
            assertThat(list.destructive()).isFalse();
            assertThat(tools.get(1).readOnly()).isFalse();
            assertThat(tools.get(1).destructive()).isTrue();
            // An absent hint is null, not false: the caller decides what "the server said nothing"
            // means, and for a tool that may write the answer is "assume it does".
            assertThat(tools.get(2).readOnly()).isNull();
            assertThat(tools.get(2).destructive()).isNull();
            assertThat(recorded.getFirst().body()).contains("\"method\":\"tools/list\"");
        });
    }

    @Test
    void sendsTheExactAcceptHeaderTheStatelessTransportRequiresAndTheTokenItWasGiven() throws Exception {
        withServer(200, TOOLS_LIST_RESPONSE, (client, recorded) -> {
            client.listTools("tok-1");

            // Anything else — including adding a q= parameter — gets an empty 400 from
            // WebMvcStatelessServerTransport, which compares media types with equals().
            assertThat(recorded.getFirst().accept()).isEqualTo("application/json, text/event-stream");
            assertThat(recorded.getFirst().authorization()).isEqualTo("Bearer tok-1");
        });
    }

    @Test
    void appendsMcpToTheBaseUrlHoweverManySlashesItEndsWith() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(new StatelessMcpClient("api", base + "///", HttpClient.newHttpClient(),
                    JsonMapper.builder().build()).endpoint().toString()).isEqualTo(base + "/mcp");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callToolSendsJsonRpcNameAndArgumentsAndFlattensTheTextContent() throws Exception {
        String ok = """
                {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\\"items\\":[]}"}]}}""";
        withServer(200, ok, (client, recorded) -> {
            McpToolResult result = client.callTool("tok-1", "dataset_search", Map.of("query", "pumps"));

            assertThat(result.isError()).isFalse();
            assertThat(result.text()).isEqualTo("{\"items\":[]}");
            String body = recorded.getLast().body();
            assertThat(body).contains("\"method\":\"tools/call\"");
            assertThat(body).contains("\"name\":\"dataset_search\"");
            assertThat(body).contains("\"query\":\"pumps\"");
        });
    }

    @Test
    void aToolThatRanAndFailedIsAResultNotAnException() throws Exception {
        String toolFailure = """
                {"jsonrpc":"2.0","id":1,"result":{"isError":true,
                 "content":[{"type":"text","text":"Dataset 42 not found"}]}}""";
        withServer(200, toolFailure, (client, recorded) -> {
            McpToolResult result = client.callTool("tok-1", "dataset_delete", Map.of("id", 42));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).isEqualTo("Dataset 42 not found");
        });
    }

    @Test
    void aJsonRpcErrorThrowsWithTheServersMessage() throws Exception {
        String rpcError = """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool: nope"}}""";
        withServer(200, rpcError, (client, recorded) ->
                assertThatThrownBy(() -> client.callTool("tok-1", "nope", Map.of()))
                        .isInstanceOf(McpException.class)
                        .hasMessageContaining("Unknown tool: nope")
                        .satisfies(e -> assertThat(((McpException) e).isUnauthorized()).isFalse()));
    }

    @Test
    void aRefusedTokenIsDistinguishableFromEveryOtherFailure() throws Exception {
        withServer(401, "", (client, recorded) ->
                assertThatThrownBy(() -> client.callTool("expired", "dataset_list", Map.of()))
                        .isInstanceOf(McpException.class)
                        .satisfies(e -> assertThat(((McpException) e).isUnauthorized()).isTrue()));
        withServer(500, "boom", (client, recorded) ->
                assertThatThrownBy(() -> client.listTools("tok-1"))
                        .isInstanceOf(McpException.class)
                        .hasMessageContaining("HTTP 500")
                        .satisfies(e -> assertThat(((McpException) e).isUnauthorized()).isFalse()));
    }

    @Test
    void aRateLimitedCallSaysHowLongToWait() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "17");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        try {
            StatelessMcpClient client = new StatelessMcpClient("datahub-api",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newHttpClient(), JsonMapper.builder().build());
            assertThatThrownBy(() -> client.callTool("tok-1", "dataset_list", Map.of()))
                    .isInstanceOf(McpException.class)
                    .satisfies(e -> {
                        assertThat(((McpException) e).retryAfter()).isEqualTo(Duration.ofSeconds(17));
                        assertThat(((McpException) e).isUnauthorized()).isFalse();
                    });
        } finally {
            server.stop(0);
        }
        // Any other failure names no wait.
        withServer(500, "boom", (client, recorded) ->
                assertThatThrownBy(() -> client.listTools("tok-1"))
                        .satisfies(e -> assertThat(((McpException) e).retryAfter()).isNull()));
    }

    @Test
    void theRunViewAsksForTheTokenOnEveryCall() throws Exception {
        withServer(200, TOOLS_LIST_RESPONSE, (client, recorded) -> {
            AtomicInteger issued = new AtomicInteger();
            McpTools run = client.as(() -> "tok-" + issued.incrementAndGet());

            run.listTools();
            run.listTools();

            assertThat(run.name()).isEqualTo("datahub-api");
            assertThat(recorded).extracting(Recorded::authorization).containsExactly("Bearer tok-1", "Bearer tok-2");
        });
    }

    // ---- harness -------------------------------------------------------------------------------

    private record Recorded(String body, String authorization, String accept) {
    }

    private interface Scenario {
        void run(StatelessMcpClient client, List<Recorded> recorded) throws Exception;
    }

    private void withServer(int status, String responseBody, Scenario scenario) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Recorded> recorded = new ArrayList<>();
        server.createContext("/mcp", exchange -> {
            recorded.add(record(exchange));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        try {
            // Base URL only — the client appends /mcp itself.
            StatelessMcpClient client = new StatelessMcpClient("datahub-api",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    HttpClient.newHttpClient(), JsonMapper.builder().build());
            scenario.run(client, recorded);
        } finally {
            server.stop(0);
        }
    }

    private static Recorded record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new Recorded(
                body,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Accept"));
    }
}
