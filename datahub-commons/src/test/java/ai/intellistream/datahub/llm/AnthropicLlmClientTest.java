// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import ai.intellistream.datahub.tenant.LlmProvider;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static ai.intellistream.datahub.llm.LlmSettingsFixture.anthropic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        withApi(textResponse("hello"), (client, settings, request) -> {
            client.send(settings, "You are a data assistant.", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);

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
        withApi(textResponse("hello"), (client, settings, request) -> {
            client.send(settings, "You are a data assistant.",
                    List.of(DATASET_LIST, UNIT_LIST), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);

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
        withApi(textResponse("done"), (client, settings, request) -> {
            List<LlmMessage> transcript = List.of(
                    LlmMessage.user("what do I have?"),
                    LlmMessage.assistant(List.of(
                            new LlmBlock.ToolUse("t1", "dataset_list", Map.of("limit", 10)),
                            new LlmBlock.ToolUse("t2", "unit_list", Map.of()))),
                    LlmMessage.toolResults(List.of(
                            new LlmBlock.ToolResult("t1", "{\"items\":[]}", false),
                            new LlmBlock.ToolResult("t2", "boom", true))));

            client.send(settings, "system", List.of(DATASET_LIST), transcript, LlmEffort.HIGH);

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
        withApi(toolUse, (client, settings, request) -> {
            LlmTurn turn = client.send(settings, "system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);

            assertThat(turn.wantsTools()).isTrue();
            assertThat(turn.stopReason()).isEqualTo(LlmTurn.StopReason.TOOL_USE);
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
    void reportsWhatTheCallCostAndWhyItStopped() throws Exception {
        // An answer cut off at max_tokens looks like an answer; only the stop reason says it is
        // not, and an unattended run has nobody to notice the missing sentence.
        String cutOff = """
                {"id":"msg_4","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":"The pump is"}],
                 "stop_reason":"max_tokens","stop_sequence":null,
                 "usage":{"input_tokens":1200,"output_tokens":4096,
                          "cache_read_input_tokens":1000,"cache_creation_input_tokens":150}}""";
        withApi(cutOff, (client, settings, request) -> {
            LlmTurn turn = client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);

            assertThat(turn.wantsTools()).isFalse();
            assertThat(turn.stopReason()).isEqualTo(LlmTurn.StopReason.MAX_TOKENS);
            assertThat(turn.usage()).isEqualTo(new LlmTurn.Usage(1200, 4096, 1000, 150));
        });
        withApi(textResponse("hello"), (client, settings, request) -> {
            LlmTurn turn = client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);

            assertThat(turn.stopReason()).isEqualTo(LlmTurn.StopReason.END_TURN);
            // Cache figures absent from the response count as zero, not as missing data.
            assertThat(turn.usage()).isEqualTo(new LlmTurn.Usage(10, 5, 0, 0));
        });
    }

    @Test
    void aRefusalBecomesAPlainAnswerRatherThanAnEmptyReply() throws Exception {
        String refusal = """
                {"id":"msg_3","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[],"stop_reason":"refusal","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":0}}""";
        withApi(refusal, (client, settings, request) -> {
            LlmTurn turn = client.send(settings, "system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);

            // Reading content[0] unconditionally here would throw; the user gets a sentence instead.
            assertThat(turn.wantsTools()).isFalse();
            assertThat(turn.stopReason()).isEqualTo(LlmTurn.StopReason.OTHER);
            assertThat(turn.text()).contains("can't help with that");
        });
    }

    @Test
    void sendsTheRequestedEffortAndAdaptiveThinking() throws Exception {
        withApi(textResponse("hello"), (client, settings, request) -> {
            client.send(settings, "system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), LlmEffort.LOW);

            assertThat(request.get().path("output_config").path("effort").asString()).isEqualTo("low");
            // Stated rather than defaulted: on an older model omitting it means no thinking at all,
            // which would leave the effort picker with nothing to modulate.
            assertThat(request.get().path("thinking").path("type").asString()).isEqualTo("adaptive");
            // Summarised, also stated: an agent's transcript shows it, and some models omit it unasked.
            assertThat(request.get().path("thinking").path("display").asString()).isEqualTo("summarized");
        });
    }

    @Test
    void keepsTheThinkingApartFromTheBlocksSentBack() throws Exception {
        String thought = """
                {"id":"msg_5","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"thinking","thinking":"The valve alarm needs its pressure series.","signature":"sig"},
                            {"type":"tool_use","id":"toolu_1","name":"dataset_list","input":{"limit":5}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}""";
        withApi(thought, (client, settings, request) -> {
            LlmTurn turn = client.send(settings, "system", List.of(DATASET_LIST), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);

            assertThat(turn.thinking()).isEqualTo("The valve alarm needs its pressure series.");
            assertThat(turn.blocks()).hasSize(1).allMatch(LlmBlock.ToolUse.class::isInstance);
            assertThat(turn.text()).isEmpty();
        });
    }

    @Test
    void withNoRoofConfiguredTheDeepestLevelsGetMoreRoom() throws Exception {
        withApi(textResponse("hello"), (client, settings, request) -> {
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), LlmEffort.HIGH);
            assertThat(request.get().path("max_tokens").asInt()).isEqualTo(4096);

            // max_tokens caps thinking and answer together, so a turn told to think its hardest
            // under a 4096 ceiling would spend the budget reasoning and truncate the answer.
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), LlmEffort.MAX);
            assertThat(request.get().path("max_tokens").asInt()).isEqualTo(32_000);
        });
    }

    @Test
    void aConfiguredRoofIsSentEvenAtTheDeepestLevel() throws Exception {
        withApi(textResponse("hello"), 800, (client, settings, request) -> {
            client.send(settings, "system", List.of(), List.of(LlmMessage.user("hi")), LlmEffort.MAX);
            assertThat(request.get().path("max_tokens").asInt()).isEqualTo(800);
        });
    }

    @Test
    void theTurnTimeoutBoundsTheCall() throws Exception {
        // A hung provider must not hold a chat request, or an agent run's slot, for the SDK's own
        // ten-minute default: the tenant's turn budget is the bound, as on the OpenAI-compatible
        // client.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            AnthropicLlmClient client = new AnthropicLlmClient(
                    AnthropicOkHttpClient.builder()
                            .apiKey("test-key")
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .maxRetries(0)
                            .build(),
                    JSON);
            LlmSettings impatient = new LlmSettings(LlmProvider.ANTHROPIC, "test-key", "claude-opus-5",
                    null, null, Duration.ofMillis(300), null, LlmEffort.DEFAULT, 6, null);

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> client.send(impatient, "system", List.of(),
                    List.of(LlmMessage.user("hi")), LlmEffort.HIGH))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        } finally {
            server.stop(0);
        }
    }

    // ---- harness -------------------------------------------------------------------------------

    private interface Scenario {
        void run(AnthropicLlmClient client, LlmSettings settings,
                 AtomicReference<JsonNode> request) throws Exception;
    }

    private void withApi(String responseBody, Scenario scenario) throws Exception {
        withApi(responseBody, null, scenario);
    }

    /** @param maxOutputTokens a configured roof, or null to let the effort level choose */
    private void withApi(String responseBody, Integer maxOutputTokens, Scenario scenario)
            throws Exception {
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
            AnthropicLlmClient client = new AnthropicLlmClient(
                    AnthropicOkHttpClient.builder()
                            .apiKey("test-key")
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .build(),
                    JSON);
            scenario.run(client, anthropic(maxOutputTokens), lastRequest);
        } finally {
            server.stop(0);
        }
    }
}
