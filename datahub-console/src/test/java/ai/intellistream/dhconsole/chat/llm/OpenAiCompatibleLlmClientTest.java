// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.config.AgentSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final LlmToolDef DATASET_LIST = new LlmToolDef(
            "dataset_list",
            "Browse datasets.",
            "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}");

    private static String reply(String content) {
        return """
                {"choices":[{"index":0,"finish_reason":"stop",
                 "message":{"role":"assistant","content":"%s"}}]}""".formatted(content);
    }

    @Test
    void sendsTheSystemPromptAsTheFirstMessageAndSchemaUnderFunctionParameters() throws Exception {
        withServer(reply("hello"), (client, settings, request) -> {
            client.send(settings, "You are a data assistant.", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);

            JsonNode body = request.get();
            assertThat(body.path("messages").path(0).path("role").asString()).isEqualTo("system");
            assertThat(body.path("messages").path(1).path("content").asString()).isEqualTo("hi");
            assertThat(body.path("stream").asBoolean()).isFalse();

            JsonNode function = body.path("tools").path(0).path("function");
            assertThat(function.path("name").asString()).isEqualTo("dataset_list");
            assertThat(function.path("parameters").path("properties").path("limit").path("type").asString())
                    .isEqualTo("integer");
        });
    }

    @Test
    void fansTheBatchedToolResultsOutToOneMessageEach() throws Exception {
        withServer(reply("done"), (client, settings, request) -> {
            List<LlmMessage> transcript = List.of(
                    LlmMessage.user("what do I have?"),
                    LlmMessage.assistant(List.of(
                            new LlmBlock.ToolUse("call_1", "dataset_list", Map.of("limit", 10)),
                            new LlmBlock.ToolUse("call_2", "unit_list", Map.of()))),
                    LlmMessage.toolResults(List.of(
                            new LlmBlock.ToolResult("call_1", "{\"items\":[]}", false),
                            new LlmBlock.ToolResult("call_2", "boom", true))));

            client.send(settings, "system", List.of(DATASET_LIST), transcript, ChatEffort.HIGH);

            JsonNode messages = request.get().path("messages");
            // system, user, assistant, tool, tool — the single batched message became two.
            assertThat(messages.size()).isEqualTo(5);
            assertThat(messages.path(3).path("role").asString()).isEqualTo("tool");
            assertThat(messages.path(3).path("tool_call_id").asString()).isEqualTo("call_1");
            assertThat(messages.path(4).path("tool_call_id").asString()).isEqualTo("call_2");

            // Arguments go back out as a JSON string, the shape this API expects.
            JsonNode assistant = messages.path(2);
            assertThat(assistant.path("tool_calls").path(0).path("function").path("arguments").asString())
                    .contains("\"limit\":10");
        });
    }

    @Test
    void parsesToolCallsFromTheResponse() throws Exception {
        String toolCall = """
                {"choices":[{"index":0,"finish_reason":"tool_calls","message":{
                  "role":"assistant","content":"",
                  "tool_calls":[{"id":"call_9","type":"function","function":{
                    "name":"dataset_list","arguments":"{\\"limit\\":25}"}}]}}]}""";
        withServer(toolCall, (client, settings, request) -> {
            LlmTurn turn = client.send(settings, "system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);

            assertThat(turn.wantsTools()).isTrue();
            assertThat(turn.toolUses()).hasSize(1);
            assertThat(turn.toolUses().getFirst().id()).isEqualTo("call_9");
            assertThat(turn.toolUses().getFirst().name()).isEqualTo("dataset_list");
            assertThat(turn.toolUses().getFirst().args()).containsEntry("limit", 25);
        });
    }

    @Test
    void malformedArgumentsDoNotKillTheTurn() throws Exception {
        // Small local models produce this regularly; the tool then fails on its own terms and the
        // model gets a chance to retry, rather than the whole request blowing up.
        String broken = """
                {"choices":[{"index":0,"finish_reason":"tool_calls","message":{
                  "role":"assistant","content":"",
                  "tool_calls":[{"id":"call_9","type":"function","function":{
                    "name":"dataset_list","arguments":"{limit: 25"}}]}}]}""";
        withServer(broken, (client, settings, request) -> {
            LlmTurn turn = client.send(settings, "system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);

            assertThat(turn.wantsTools()).isTrue();
            assertThat(turn.toolUses().getFirst().args()).isEmpty();
        });
    }

    @Test
    void noAuthorizationHeaderWhenNoKeyIsConfigured() throws Exception {
        // Ollama rejects nothing, but sending a bogus bearer to a gateway that checks it would.
        withServer(reply("hi"), (client, settings, request, headers) -> {
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);
            assertThat(headers.get()).isNull();
        });
    }

    @Test
    void omitsReasoningEffortUnlessTheServerIsKnownToSupportIt() throws Exception {
        // "OpenAI-compatible" is a family, not a specification: a strict server 400s on a field it
        // does not know, so an upgrade must not start sending one to a working deployment.
        withServer(reply("hi"), (client, settings, request) -> {
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.MAX);
            assertThat(request.get().has("reasoning_effort")).isFalse();
        });
    }

    @Test
    void mappedSendsTheLevelTheUserPicked() throws Exception {
        withServer(reply("hi"), "mapped", (client, settings, request) -> {
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.LOW);
            assertThat(request.get().path("reasoning_effort").asString()).isEqualTo("low");

            // Only three values exist on this wire, so the two deep levels collapse onto "high"
            // rather than sending a value the server would reject.
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.MAX);
            assertThat(request.get().path("reasoning_effort").asString()).isEqualTo("high");
        });
    }

    @Test
    void anyOtherValueIsSentVerbatim() throws Exception {
        // How "none" is reachable at all: no effort level maps to it, and a self-hosted thinking
        // model that spends its budget reasoning returns nothing this client can read.
        withServer(reply("hi"), "none", (client, settings, request) -> {
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.MAX);
            assertThat(request.get().path("reasoning_effort").asString()).isEqualTo("none");
        });
    }

    @Test
    void aBlankValueSendsNothing() throws Exception {
        withServer(reply("hi"), "   ", (client, settings, request) -> {
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.LOW);
            assertThat(request.get().has("reasoning_effort")).isFalse();
        });
    }

    // ---- harness -------------------------------------------------------------------------------

    private interface Scenario {
        void run(OpenAiCompatibleLlmClient client, AgentSettings settings,
                 AtomicReference<JsonNode> request) throws Exception;
    }

    private interface HeaderScenario {
        void run(OpenAiCompatibleLlmClient client, AgentSettings settings,
                 AtomicReference<JsonNode> request, AtomicReference<String> authorization)
                throws Exception;
    }

    private void withServer(String responseBody, Scenario scenario) throws Exception {
        withServer(responseBody, null,
                (client, settings, request, headers) -> scenario.run(client, settings, request));
    }

    private void withServer(String responseBody, String reasoningEffort, Scenario scenario)
            throws Exception {
        withServer(responseBody, reasoningEffort,
                (client, settings, request, headers) -> scenario.run(client, settings, request));
    }

    private void withServer(String responseBody, HeaderScenario scenario) throws Exception {
        withServer(responseBody, null, scenario);
    }

    private void withServer(String responseBody, String reasoningEffort,
                            HeaderScenario scenario) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastRequest.set(JSON.readTree(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            // Built here rather than from the shared fixture because reasoning-effort is the field
            // half these cases exist to vary, and it reads better next to what it affects.
            AgentSettings settings = new AgentSettings("test-agent", LlmProvider.OPENAI_COMPATIBLE,
                    null, "qwen3.5:latest", baseUrl, reasoningEffort, Duration.ofMinutes(4), null,
                    List.of("dataset_list"), ChatEffort.HIGH, null, 6, 40, 24_000);
            // No api key: Ollama needs none, and one of the cases below asserts that no
            // Authorization header is sent when there is nothing to send.
            scenario.run(new OpenAiCompatibleLlmClient(baseUrl, null, JSON, HttpClient.newHttpClient()),
                    settings, lastRequest, authorization);
        } finally {
            server.stop(0);
        }
    }
}
