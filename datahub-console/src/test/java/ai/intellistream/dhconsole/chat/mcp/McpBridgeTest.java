// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.mcp;

import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the bridge against real loopback HTTP servers, following the repo convention for
 * outbound-HTTP tests (see datahub-java-sdk's service tests and its CLAUDE.md — no mocking
 * framework for the wire). One server plays datahub-api; where routing matters a second plays
 * datahub-analysis.
 */
class McpBridgeTest {

    private static final String TOOLS_LIST_RESPONSE = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"dataset_list","description":"Browse datasets.",
               "inputSchema":{"type":"object","properties":{"limit":{"type":"integer"}}}},
              {"name":"dataset_delete","description":"Delete one dataset.",
               "inputSchema":{"type":"object","properties":{"id":{"type":"integer"}}}}
            ]}}""";

    private static final String ANALYSIS_LIST_RESPONSE = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"analysis_related_series","description":"Rank statistically related series.",
               "inputSchema":{"type":"object","properties":{"focusExternalId":{"type":"string"}}}}
            ]}}""";

    private static final String CALL_OK_RESPONSE = """
            {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{}"}]}}""";

    @Test
    void listToolsMapsNameDescriptionAndRawSchema() throws Exception {
        withServer(TOOLS_LIST_RESPONSE, (bridge, recorded) -> {
            List<LlmToolDef> tools = bridge.listTools("tok-1");

            assertThat(tools).extracting(LlmToolDef::name)
                    .containsExactly("dataset_list", "dataset_delete");
            assertThat(tools.getFirst().description()).isEqualTo("Browse datasets.");
            // The schema is passed through as raw JSON — both provider adapters send it verbatim.
            assertThat(tools.getFirst().inputSchemaJson())
                    .contains("\"type\":\"object\"")
                    .contains("\"limit\"");
            assertThat(recorded.getFirst().body()).contains("\"method\":\"tools/list\"");
        });
    }

    @Test
    void sendsTheExactAcceptHeaderTheStatelessTransportRequires() throws Exception {
        withServer(TOOLS_LIST_RESPONSE, (bridge, recorded) -> {
            bridge.listTools("tok-1");

            // Anything else — including adding a q= parameter — gets an empty 400 from
            // WebMvcStatelessServerTransport, which compares media types with equals().
            assertThat(recorded.getFirst().accept()).isEqualTo("application/json, text/event-stream");
        });
    }

    @Test
    void everyCallCarriesTheTokenItWasGivenAndNeverCachesIt() throws Exception {
        withServer(CALL_OK_RESPONSE, (bridge, recorded) -> {
            bridge.callTool("token-alice", "dataset_list", Map.of());
            bridge.callTool("token-bob", "dataset_list", Map.of());

            // The bearer is a method parameter precisely so that two users in the same JVM cannot
            // borrow each other's identity — and so the bridge can never become a service account.
            // (A cold first call also refreshes the tool list; every request, whatever its method,
            // must carry the token of the call that triggered it.)
            assertThat(recorded).isNotEmpty().allSatisfy(r ->
                    assertThat(r.authorization()).startsWith("Bearer token-"));
            assertThat(recorded.stream().filter(r -> r.body().contains("\"method\":\"tools/call\"")))
                    .extracting(Recorded::authorization)
                    .containsExactly("Bearer token-alice", "Bearer token-bob");
        });
    }

    @Test
    void callToolSendsJsonRpcNameAndArguments() throws Exception {
        String ok = """
                {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\\"items\\":[]}"}]}}""";
        withServer(ok, (bridge, recorded) -> {
            McpToolResult result = bridge.callTool("tok-1", "dataset_search", Map.of("query", "pumps"));

            assertThat(result.isError()).isFalse();
            assertThat(result.text()).isEqualTo("{\"items\":[]}");

            String body = recorded.getLast().body();
            assertThat(body).contains("\"method\":\"tools/call\"");
            assertThat(body).contains("\"name\":\"dataset_search\"");
            assertThat(body).contains("\"query\":\"pumps\"");
        });
    }

    @Test
    void jsonRpcErrorBecomesAnErrorResultRatherThanAnException() throws Exception {
        String rpcError = """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool: nope"}}""";
        withServer(rpcError, (bridge, recorded) -> {
            McpToolResult result = bridge.callTool("tok-1", "nope", Map.of());

            // The model has to see this as a tool result so it can adapt; throwing would abort
            // the turn and lose the conversation.
            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("Unknown tool: nope");
        });
    }

    @Test
    void resultIsErrorFlagIsPropagated() throws Exception {
        String toolFailure = """
                {"jsonrpc":"2.0","id":1,"result":{"isError":true,
                 "content":[{"type":"text","text":"Dataset 42 not found"}]}}""";
        withServer(toolFailure, (bridge, recorded) -> {
            McpToolResult result = bridge.callTool("tok-1", "dataset_delete", Map.of("id", 42));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).isEqualTo("Dataset 42 not found");
        });
    }

    @Test
    void unauthorizedIsDistinguishableSoTheUserCanBeAskedToSignInAgain() throws Exception {
        withServer(401, "", (bridge, recorded) -> assertThatThrownBy(
                () -> bridge.callTool("expired", "dataset_list", Map.of()))
                .isInstanceOf(McpException.class)
                .satisfies(e -> assertThat(((McpException) e).isUnauthorized()).isTrue()));
    }

    @Test
    void serverErrorMeansNoToolsRatherThanNoChat() throws Exception {
        // This is what a wrong Accept header — or a misconfigured server — looks like. The chat
        // must still answer from whatever other servers offer, so listTools degrades, not throws.
        withServer(400, "", (bridge, recorded) ->
                assertThat(bridge.listTools("tok-1")).isEmpty());
    }

    @Test
    void routesEachToolToTheServerThatAdvertisedIt() throws Exception {
        withTwoServers((bridge, apiRecorded, analysisRecorded) -> {
            List<LlmToolDef> tools = bridge.listTools("tok-1");

            // Union, primary server's tools first.
            assertThat(tools).extracting(LlmToolDef::name)
                    .containsExactly("dataset_list", "dataset_delete", "analysis_related_series");

            bridge.callTool("tok-1", "analysis_related_series", Map.of("focusExternalId", "pump_flow"));
            bridge.callTool("tok-1", "dataset_list", Map.of());

            // Routing is learned from tools/list, not guessed from a name prefix.
            assertThat(analysisRecorded.stream().filter(r -> r.body().contains("tools/call")))
                    .singleElement()
                    .satisfies(r -> assertThat(r.body()).contains("analysis_related_series"));
            assertThat(apiRecorded.stream().filter(r -> r.body().contains("tools/call")))
                    .singleElement()
                    .satisfies(r -> assertThat(r.body()).contains("dataset_list"));
        });
    }

    @Test
    void unreachableAnalysisServerLeavesTheApiToolsAvailable() throws Exception {
        HttpServer api = serveJson(TOOLS_LIST_RESPONSE, new ArrayList<>());
        // A port with nothing listening: the analysis service simply isn't deployed here. Bind to
        // learn a free port, then release it so connections are refused rather than left hanging
        // (HttpServer.create binds the socket immediately, even before start()).
        HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int deadPort = dead.getAddress().getPort();
        dead.stop(0);
        try {
            McpBridge bridge = new McpBridge(JsonMapper.builder().build(),
                    "http://127.0.0.1:" + api.getAddress().getPort(),
                    "http://127.0.0.1:" + deadPort);

            assertThat(bridge.listTools("tok-1"))
                    .extracting(LlmToolDef::name)
                    .containsExactly("dataset_list", "dataset_delete");
        } finally {
            api.stop(0);
        }
    }

    // ---- harness -------------------------------------------------------------------------------

    private record Recorded(String body, String authorization, String accept) {
    }

    private interface Scenario {
        void run(McpBridge bridge, List<Recorded> recorded) throws Exception;
    }

    private interface TwoServerScenario {
        void run(McpBridge bridge, List<Recorded> apiRecorded, List<Recorded> analysisRecorded) throws Exception;
    }

    private void withServer(String responseBody, Scenario scenario) throws Exception {
        withServer(200, responseBody, scenario);
    }

    private void withServer(int status, String responseBody, Scenario scenario) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Recorded> recorded = new ArrayList<>();
        server.createContext("/mcp", exchange -> {
            recorded.add(record(exchange));
            respond(exchange, status, responseBody);
        });
        server.start();
        try {
            // Base URL only — the bridge appends /mcp itself, so this covers that too. The
            // trailing slash checks the normalisation. A blank analysis URL disables that server,
            // matching a deployment without datahub-analysis.
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            McpBridge bridge = new McpBridge(JsonMapper.builder().build(), baseUrl, "");
            scenario.run(bridge, recorded);
        } finally {
            server.stop(0);
        }
    }

    private void withTwoServers(TwoServerScenario scenario) throws Exception {
        List<Recorded> apiRecorded = new ArrayList<>();
        List<Recorded> analysisRecorded = new ArrayList<>();
        HttpServer api = serveListOrCall(TOOLS_LIST_RESPONSE, apiRecorded);
        HttpServer analysis = serveListOrCall(ANALYSIS_LIST_RESPONSE, analysisRecorded);
        try {
            McpBridge bridge = new McpBridge(JsonMapper.builder().build(),
                    "http://127.0.0.1:" + api.getAddress().getPort(),
                    "http://127.0.0.1:" + analysis.getAddress().getPort());
            scenario.run(bridge, apiRecorded, analysisRecorded);
        } finally {
            api.stop(0);
            analysis.stop(0);
        }
    }

    /** Serves the given tools/list body, and a generic OK for tools/call. */
    private HttpServer serveListOrCall(String listResponse, List<Recorded> recorded) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            Recorded r = record(exchange);
            recorded.add(r);
            respond(exchange, 200, r.body().contains("tools/list") ? listResponse : CALL_OK_RESPONSE);
        });
        server.start();
        return server;
    }

    private HttpServer serveJson(String responseBody, List<Recorded> recorded) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            recorded.add(record(exchange));
            respond(exchange, 200, responseBody);
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String responseBody) throws IOException {
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            exchange.getResponseBody().write(out);
        }
        exchange.close();
    }

    private static Recorded record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new Recorded(
                body,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Accept"));
    }
}
