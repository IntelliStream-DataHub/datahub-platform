// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.ChatProperties;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the adapter against a local server standing in for the Anthropic API, so the request
 * serialisation and response mapping are covered without a key or any spend. The shapes asserted
 * here — batched tool results, the cache breakpoint, schema pass-through — are the ones that fail
 * as opaque 400s in production if they drift.
 */
class AnthropicLlmClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final LlmToolDef DATASET_LIST = new LlmToolDef(
            "dataset_list",
            "Browse datasets.",
            """
            {"type":"object","properties":{"limit":{"type":"integer","description":"Max rows"}},
             "required":["limit"]}""");

    private static final LlmToolDef UNIT_LIST = new LlmToolDef(
            "unit_list",
            "List units of measure.",
            """
            {"type":"object","properties":{}}""");

    private static String textResponse(String text) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":"%s"}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}""".formatted(text);
    }

    @Test
    void sendsToolSchemaThroughUnchanged() throws Exception {
        withApi(textResponse("hello"), (client, request) -> {
            client.send("You are a data assistant.", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);

            JsonNode tool = request.get().path("tools").path(0);
            assertThat(tool.path("name").asString()).isEqualTo("dataset_list");
            assertThat(tool.path("description").asString()).isEqualTo("Browse datasets.");
            // Straight from datahub-api's tools/list — nothing in between reinterprets it.
            assertThat(tool.path("input_schema").path("properties").path("limit").path("type").asString())
                    .isEqualTo("integer");
            assertThat(tool.path("input_schema").path("required").path(0).asString()).isEqualTo("limit");
        });
    }

    @Test
    void marksTheStaticPrefixCacheable() throws Exception {
        withApi(textResponse("hello"), (client, request) -> {
            client.send("You are a data assistant.",
                    List.of(DATASET_LIST, UNIT_LIST), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);

            // Two breakpoints — the system block and the LAST tool — so both static sections are
            // cached. A breakpoint on the system block alone leaves the tool schemas (the bulk of
            // the prompt) re-billed at full input price every turn, which the usage logs confirmed.
            assertThat(request.get().path("system").path(0).path("cache_control").path("type").asString())
                    .isEqualTo("ephemeral");
            JsonNode tools = request.get().path("tools");
            assertThat(tools.path(tools.size() - 1).path("cache_control").path("type").asString())
                    .isEqualTo("ephemeral");
            // Earlier tools must NOT carry a breakpoint — only the last one closes the cached range.
            assertThat(tools.path(0).path("cache_control").isMissingNode()).isTrue();
        });
    }

    @Test
    void sendsAllToolResultsAsOneUserMessage() throws Exception {
        withApi(textResponse("done"), (client, request) -> {
            List<LlmMessage> transcript = List.of(
                    LlmMessage.user("what do I have?"),
                    LlmMessage.assistant(List.of(
                            new LlmBlock.ToolUse("t1", "dataset_list", Map.of("limit", 10)),
                            new LlmBlock.ToolUse("t2", "unit_list", Map.of()))),
                    LlmMessage.toolResults(List.of(
                            new LlmBlock.ToolResult("t1", "{\"items\":[]}", false),
                            new LlmBlock.ToolResult("t2", "boom", true))));

            client.send("system", List.of(DATASET_LIST), transcript, ChatEffort.HIGH);

            JsonNode messages = request.get().path("messages");
            assertThat(messages.size()).isEqualTo(3);

            JsonNode results = messages.path(2);
            assertThat(results.path("role").asString()).isEqualTo("user");
            assertThat(results.path("content").size()).isEqualTo(2);
            assertThat(results.path("content").path(0).path("type").asString()).isEqualTo("tool_result");
            assertThat(results.path("content").path(0).path("tool_use_id").asString()).isEqualTo("t1");
            assertThat(results.path("content").path(1).path("is_error").asBoolean()).isTrue();

            // The assistant turn must replay its tool_use blocks, arguments intact, or the results
            // have nothing to attach to.
            JsonNode assistant = messages.path(1);
            assertThat(assistant.path("content").path(0).path("input").path("limit").asInt()).isEqualTo(10);
        });
    }

    @Test
    void mapsAToolUseResponseIntoTheNeutralShape() throws Exception {
        String toolUse = """
                {"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":"Let me look."},
                            {"type":"tool_use","id":"toolu_9","name":"dataset_list",
                             "input":{"limit":25,"query":"pumps"}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}""";
        withApi(toolUse, (client, request) -> {
            LlmTurn turn = client.send("system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);

            assertThat(turn.wantsTools()).isTrue();
            assertThat(turn.text()).isEqualTo("Let me look.");
            assertThat(turn.toolUses()).hasSize(1);
            LlmBlock.ToolUse call = turn.toolUses().getFirst();
            assertThat(call.id()).isEqualTo("toolu_9");
            assertThat(call.name()).isEqualTo("dataset_list");
            assertThat(call.args()).containsEntry("query", "pumps");
            assertThat(call.args()).containsKey("limit");
        });
    }

    @Test
    void aRefusalBecomesAPlainAnswerRatherThanAnEmptyReply() throws Exception {
        String refusal = """
                {"id":"msg_3","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[],"stop_reason":"refusal","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":0}}""";
        withApi(refusal, (client, request) -> {
            LlmTurn turn = client.send("system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);

            // Reading content[0] unconditionally here would throw; the user gets a sentence instead.
            assertThat(turn.wantsTools()).isFalse();
            assertThat(turn.text()).contains("can't help with that");
        });
    }

    @Test
    void sendsTheRequestedEffortAndAdaptiveThinking() throws Exception {
        withApi(textResponse("hello"), (client, request) -> {
            client.send("system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), ChatEffort.LOW);

            assertThat(request.get().path("output_config").path("effort").asString()).isEqualTo("low");
            // Stated rather than defaulted: on an older model omitting it means no thinking at all,
            // which would leave the effort picker with nothing to modulate.
            assertThat(request.get().path("thinking").path("type").asString()).isEqualTo("adaptive");
        });
    }

    @Test
    void withNoRoofConfiguredTheDeepestLevelsGetMoreRoom() throws Exception {
        withApi(textResponse("hello"), (client, request) -> {
            client.send("system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.HIGH);
            assertThat(request.get().path("max_tokens").asInt()).isEqualTo(4096);

            // max_tokens caps thinking and answer together, so a turn told to think its hardest
            // under a 4096 ceiling would spend the budget reasoning and truncate the answer.
            client.send("system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.MAX);
            assertThat(request.get().path("max_tokens").asInt()).isEqualTo(32_000);
        });
    }

    @Test
    void aConfiguredRoofIsSentEvenAtTheDeepestLevel() throws Exception {
        withApi(textResponse("hello"), properties -> properties.setMaxOutputTokens(800),
                (client, request) -> {
                    client.send("system", List.of(), List.of(LlmMessage.user("hi")), ChatEffort.MAX);
                    assertThat(request.get().path("max_tokens").asInt()).isEqualTo(800);
                });
    }

    // ---- harness -------------------------------------------------------------------------------

    private interface Scenario {
        void run(AnthropicLlmClient client, AtomicReference<JsonNode> request) throws Exception;
    }

    private void withApi(String responseBody, Scenario scenario) throws Exception {
        withApi(responseBody, properties -> { }, scenario);
    }

    private void withApi(String responseBody, java.util.function.Consumer<ChatProperties> configure,
                         Scenario scenario) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
        server.createContext("/v1/messages", exchange -> {
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
            ChatProperties properties = new ChatProperties();
            properties.setModel("claude-opus-5");
            configure.accept(properties);
            AnthropicLlmClient client = new AnthropicLlmClient(
                    AnthropicOkHttpClient.builder()
                            .apiKey("test-key")
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .build(),
                    properties,
                    JSON);
            scenario.run(client, lastRequest);
        } finally {
            server.stop(0);
        }
    }
}
