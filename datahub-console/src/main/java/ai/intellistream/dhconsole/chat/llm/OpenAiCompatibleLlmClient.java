// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.ChatSettings;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} for any server speaking the OpenAI chat-completions API — Ollama, vLLM,
 * llama.cpp, LM Studio. This is the airgapped path: no key required, nothing leaves the network.
 *
 * <p>Two shape differences from Anthropic are absorbed here so the loop never sees them:
 * <ul>
 *   <li>tool results are one {@code role:"tool"} message each, not a batch of blocks on one user
 *       message;</li>
 *   <li>tool-call arguments arrive as a JSON <em>string</em> that has to be parsed — and small
 *       models get that string wrong often enough that a parse failure is handled as a tool error
 *       rather than an exception.</li>
 * </ul>
 *
 * <p>Requests are non-streaming, which is what makes this adapter simple: the fragmented
 * {@code delta.tool_calls[i].function.arguments} accumulation that streaming would require does
 * not arise.
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

    /** The one value of {@code datahub.chat.reasoning-effort} that is not sent verbatim. */
    private static final String MAPPED = "mapped";

    private final HttpClient http;
    private final JsonMapper json;
    private final URI endpoint;
    private final String apiKey;

    /**
     * The endpoint and the credential are instance state, not per-call arguments, because they are
     * what one cached instance <em>is</em>: {@code LlmBackends} keys its cache on exactly this
     * pair, so every turn reaching a given instance already agreed on them. Everything that varies
     * per tenant — model, budgets, effort — arrives with the call.
     */
    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, JsonMapper json) {
        this(baseUrl, apiKey, json,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    OpenAiCompatibleLlmClient(String baseUrl, String apiKey, JsonMapper json, HttpClient http) {
        this.json = json;
        this.http = http;
        this.apiKey = apiKey;
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (base.isBlank()) {
            throw new IllegalStateException(
                    "A base URL is required for provider=openai-compatible — set it on the tenant's "
                            + "tenant-llm entry, or as datahub.chat.base-url for the deployment "
                            + "default (e.g. http://localhost:11434/v1 for Ollama).");
        }
        this.endpoint = URI.create(base + "/chat/completions");
    }

    @Override
    public LlmTurn send(ChatSettings settings, String systemPrompt, List<LlmToolDef> tools,
                        List<LlmMessage> messages, ChatEffort effort) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("stream", false);
        body.put("max_tokens", settings.maxOutputTokensFor(effort));
        // Opt-in: a server that validates its request model strictly rejects a field it does not
        // know, so an upgrade must not start sending one to a working deployment. See
        // ChatProperties#reasoningEffort for the three modes.
        String reasoningEffort = settings.reasoningEffort();
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning_effort", MAPPED.equalsIgnoreCase(reasoningEffort.strip())
                    ? effort.openAiReasoningEffort()
                    : reasoningEffort.strip());
        }
        body.put("messages", toWireMessages(systemPrompt, messages));
        if (!tools.isEmpty()) {
            body.put("tools", toWireTools(tools));
            body.put("tool_choice", "auto");
        }

        JsonNode message = post(body, settings.turnTimeout()).path("choices").path(0).path("message");

        List<LlmBlock> blocks = new ArrayList<>();
        String content = message.path("content").asString("");
        if (!content.isBlank()) {
            blocks.add(new LlmBlock.Text(content));
        }

        boolean wantsTools = false;
        for (JsonNode call : message.path("tool_calls").values()) {
            wantsTools = true;
            String id = call.path("id").asString("");
            String name = call.path("function").path("name").asString("");
            blocks.add(new LlmBlock.ToolUse(id, name, parseArguments(call)));
        }
        return new LlmTurn(blocks, wantsTools);
    }

    @Override
    public String providerId(ChatSettings settings) {
        return "openai-compatible/" + settings.model();
    }

    private List<Map<String, Object>> toWireMessages(String systemPrompt, List<LlmMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>();
        wire.add(Map.of("role", "system", "content", systemPrompt));

        for (LlmMessage message : messages) {
            List<LlmBlock.ToolResult> results = message.blocks().stream()
                    .filter(LlmBlock.ToolResult.class::isInstance)
                    .map(LlmBlock.ToolResult.class::cast)
                    .toList();

            if (!results.isEmpty()) {
                // The batch the loop keeps as one message fans out to one message per result here.
                for (LlmBlock.ToolResult result : results) {
                    wire.add(Map.of(
                            "role", "tool",
                            "tool_call_id", result.toolUseId(),
                            "content", result.content()));
                }
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.role() == LlmMessage.Role.USER ? "user" : "assistant");
            entry.put("content", message.text());

            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (LlmBlock block : message.blocks()) {
                if (block instanceof LlmBlock.ToolUse call) {
                    toolCalls.add(Map.of(
                            "id", call.id(),
                            "type", "function",
                            "function", Map.of(
                                    "name", call.name(),
                                    // Arguments go back as a string, the same way they arrived.
                                    "arguments", json.writeValueAsString(call.args()))));
                }
            }
            if (!toolCalls.isEmpty()) {
                entry.put("tool_calls", toolCalls);
            }
            wire.add(entry);
        }
        return wire;
    }

    private List<Map<String, Object>> toWireTools(List<LlmToolDef> tools) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (LlmToolDef tool : tools) {
            wire.add(Map.of("type", "function", "function", Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    // Same JSON Schema Anthropic gets — no conversion on either path.
                    "parameters", json.readTree(tool.inputSchemaJson()))));
        }
        return wire;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(JsonNode call) {
        String arguments = call.path("function").path("arguments").asString("");
        if (arguments.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(arguments, Map.class);
        } catch (JacksonException e) {
            // Small models emit malformed argument JSON regularly. Returning empty args lets the
            // tool fail on its own terms and the model retry, instead of killing the turn.
            log.warn("Model produced unparseable tool arguments for {}: {}",
                    call.path("function").path("name").asString(""), arguments);
            return Map.of();
        }
    }

    private JsonNode post(Map<String, Object> body, Duration turnTimeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // The turn budget, not a constant: a self-hosted model on CPU can spend minutes
                // on one call, and whoever raised the budget meant this call too.
                .timeout(turnTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body), StandardCharsets.UTF_8));
        // Ollama needs no key; vLLM behind a gateway usually does.
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + endpoint, e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the model server at " + endpoint, e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Model server returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return json.readTree(response.body());
    }
}
