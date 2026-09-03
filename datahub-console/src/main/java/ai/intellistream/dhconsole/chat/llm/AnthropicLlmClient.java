// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.ChatSettings;
import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} backed by the Anthropic API.
 *
 * <p>All Anthropic-specific shapes are confined to this class: the batched tool-result message, the
 * {@code stop_reason} vocabulary, prompt-cache breakpoints, and the refusal stop reason. The loop
 * and the stored transcript stay neutral, so a second provider is a sibling of this file.
 */
@Slf4j
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final JsonMapper json;

    /**
     * The credential is bound into {@code client} and everything else arrives with the call: one
     * instance is shared by every tenant on the same key, and they do not share a model.
     */
    public AnthropicLlmClient(AnthropicClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public LlmTurn send(ChatSettings settings, String systemPrompt, List<LlmToolDef> tools,
                        List<LlmMessage> messages, ChatEffort effort) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(settings.maxOutputTokensFor(effort))
                .outputConfig(OutputConfig.builder().effort(toEffort(effort)).build())
                // Stated rather than left to the default because the default differs by model: on
                // claude-opus-5 omitting it thinks anyway, on 4.8/4.7 omitting it does not think at
                // all — and effort is largely a control over thinking depth, so a tenant pointing
                // at an older model would otherwise see the picker do nothing.
                .thinking(ThinkingConfigAdaptive.builder().build())
                // One breakpoint on the system block, one on the last tool (below). The tool list is
                // the biggest static chunk (~19k tokens), and a breakpoint on the system block alone
                // does NOT cache it — it was re-billed at full input price every turn. Caching both
                // sections cuts per-turn latency and cost sharply from the second turn on. Keep the
                // tool ordering and the system prefix byte-stable or the cache misses.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));

        for (int i = 0; i < tools.size(); i++) {
            // The breakpoint goes on the LAST tool so the whole tool array is cached.
            params.addTool(toAnthropicTool(tools.get(i), i == tools.size() - 1));
        }
        for (LlmMessage message : messages) {
            params.addMessage(toMessageParam(message));
        }

        Message response = client.messages().create(params.build());

        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            // A safety classifier declined. Content is empty or partial, so don't read it as an
            // answer — say so plainly instead of surfacing a blank reply.
            log.info("Anthropic declined the request (stop_reason=refusal)");
            return new LlmTurn(List.of(new LlmBlock.Text(
                    "I can't help with that request. Try rephrasing it, or ask about your data directly.")), false);
        }

        List<LlmBlock> blocks = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> blocks.add(new LlmBlock.Text(t.text())));
            block.toolUse().ifPresent(t -> blocks.add(
                    new LlmBlock.ToolUse(t.id(), t.name(), toArgumentMap(t._input()))));
        }

        if (response.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            // The configured roof wins over the effort level by design, so this is the one place the
            // cost of that shows up. Without it a truncated answer just looks like a poor answer.
            log.warn("Answer truncated at the {}-token ceiling (effort {}). Raise "
                            + "datahub.chat.max-output-tokens, or ask at a lower effort.",
                    settings.maxOutputTokensFor(effort), effort);
        }

        boolean wantsTools = response.stopReason().filter(StopReason.TOOL_USE::equals).isPresent();
        logUsage(response);
        return new LlmTurn(blocks, wantsTools);
    }

    @Override
    public String providerId(ChatSettings settings) {
        return "anthropic/" + settings.model();
    }

    private static OutputConfig.Effort toEffort(ChatEffort effort) {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
            case XHIGH -> OutputConfig.Effort.XHIGH;
            case MAX -> OutputConfig.Effort.MAX;
        };
    }

    private Tool toAnthropicTool(LlmToolDef def, boolean cacheable) {
        JsonNode schema = json.readTree(def.inputSchemaJson());

        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
            // The schema is passed through as the api wrote it; nothing here interprets it.
            props.putAdditionalProperty(property.getKey(), JsonValue.from(toPlainJava(property.getValue())));
        }

        Tool.InputSchema.Builder inputSchema = Tool.InputSchema.builder().properties(props.build());
        List<String> required = new ArrayList<>();
        for (JsonNode name : schema.path("required").values()) {
            required.add(name.asString());
        }
        if (!required.isEmpty()) {
            inputSchema.required(required);
        }

        Tool.Builder builder = Tool.builder()
                .name(def.name())
                .description(def.description())
                .inputSchema(inputSchema.build());
        if (cacheable) {
            builder.cacheControl(CacheControlEphemeral.builder().build());
        }
        return builder.build();
    }

    private MessageParam toMessageParam(LlmMessage message) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (LlmBlock block : message.blocks()) {
            switch (block) {
                case LlmBlock.Text text -> blocks.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(text.text()).build()));
                case LlmBlock.ToolUse call -> blocks.add(ContentBlockParam.ofToolUse(
                        ToolUseBlockParam.builder()
                                .id(call.id())
                                .name(call.name())
                                .input(toInput(call.args()))
                                .build()));
                case LlmBlock.ToolResult result -> blocks.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(result.toolUseId())
                                .content(result.content())
                                .isError(result.isError())
                                .build()));
            }
        }
        return MessageParam.builder()
                .role(message.role() == LlmMessage.Role.USER
                        ? MessageParam.Role.USER
                        : MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }

    private static ToolUseBlockParam.Input toInput(Map<String, Object> args) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        args.forEach((key, value) -> input.putAdditionalProperty(key, JsonValue.from(value)));
        return input.build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toArgumentMap(JsonValue input) {
        Object converted = input.convert(Map.class);
        return converted == null ? Map.of() : (Map<String, Object>) converted;
    }

    /**
     * Jackson 3 {@link JsonNode} to plain Java, because {@link JsonValue#from(Object)} serialises
     * with the SDK's bundled Jackson 2 and would otherwise treat a Jackson 3 node as an opaque bean.
     */
    private static Object toPlainJava(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(e -> map.put(e.getKey(), toPlainJava(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.values().forEach(child -> list.add(toPlainJava(child)));
            return list;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asString();
    }

    private void logUsage(Message response) {
        if (!log.isDebugEnabled()) {
            return;
        }
        var usage = response.usage();
        // cacheReadInputTokens should be non-zero from the second turn onwards; if it stays at
        // zero the tool list or system prompt is changing between requests.
        log.debug("Anthropic turn: input={} output={} cacheWrite={} cacheRead={}",
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
    }
}
