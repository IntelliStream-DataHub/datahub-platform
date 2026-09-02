// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.AgentSettings;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} backed by Spring AI's Anthropic model.
 *
 * <h3>Why Spring AI here and not everywhere</h3>
 * Spring AI's Anthropic module wraps the same official {@code anthropic-java} SDK this class used
 * to drive by hand, at the same version, so nothing is lost by delegating — and a good deal is
 * gained. The hand-written version had to build Anthropic's {@code Tool.InputSchema} property by
 * property, and bridge Jackson 3 nodes to plain Java because the SDK bundles Jackson 2. Spring AI
 * takes the JSON schema as the string {@link LlmToolDef} already carries, so both of those
 * disappear. Prompt-cache breakpoints, which had to be placed by hand on the system block and the
 * last tool, become {@link AnthropicCacheStrategy#SYSTEM_AND_TOOLS}.
 *
 * <p>Its sibling {@link OpenAiCompatibleLlmClient} is deliberately <em>not</em> migrated. See that
 * class for why.
 *
 * <h3>Why {@code ChatModel} and not {@code ChatClient}</h3>
 * In Spring AI 2.0 the tool-calling loop lives in {@code ChatClient}'s {@code ToolCallingAdvisor};
 * {@code ChatModel.call} resolves tool <em>definitions</em> for the request and returns whatever
 * tool calls come back without executing any of them. That is exactly the seam this codebase
 * needs: the loop keeps ownership of execution, so the tool policy and the caller's permissions
 * cannot be bypassed by the framework helpfully running a tool on its own.
 *
 * <p>The {@link ToolCallback}s handed over therefore carry a definition and nothing else. If one
 * is ever actually invoked, that assumption has broken, and it says so loudly rather than
 * executing something unpoliced.
 */
@Slf4j
public class SpringAiAnthropicLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> ARGS = new TypeReference<>() {};

    /** @see #toSpringAiMessages(List) */
    static final String TOOL_ERROR_PREFIX = "[tool error] ";

    private final ChatModel chatModel;
    private final JsonMapper json;

    public SpringAiAnthropicLlmClient(ChatModel chatModel, JsonMapper json) {
        this.chatModel = chatModel;
        this.json = json;
    }

    @Override
    public LlmTurn send(AgentSettings settings, String systemPrompt, List<LlmToolDef> tools,
                        List<LlmMessage> messages, ChatEffort effort) {

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(settings.model())
                .maxTokens(settings.maxOutputTokensFor(effort))
                .effort(toEffort(effort))
                // Stated rather than left to the default because the default differs by model: on
                // claude-opus-5 omitting it thinks anyway, on 4.8/4.7 omitting it does not think at
                // all — and effort is largely a control over thinking depth, so a tenant pointing
                // at an older model would otherwise see the picker do nothing.
                .thinkingAdaptive()
                // The system block and the tool array are the two big static chunks (the tools run
                // to roughly 19k tokens), and caching the system block alone does NOT cache the
                // tools — they were re-billed at full input price every turn. Keep the tool
                // ordering and the system prefix byte-stable or the cache misses.
                .cacheOptions(AnthropicCacheOptions.builder()
                        .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                        .build())
                .toolCallbacks(tools.stream().map(DefinitionOnlyToolCallback::new)
                        .map(ToolCallback.class::cast).toList())
                .build();

        List<Message> wire = new ArrayList<>();
        wire.add(new SystemMessage(systemPrompt));
        wire.addAll(toSpringAiMessages(messages));

        ChatResponse response = chatModel.call(new Prompt(wire, options));

        // The SDK's own stop reason, not Spring AI's rendering of it. Spring AI normalises finish
        // reasons for portability and "refusal" has no portable equivalent, so reading its string
        // form would silently stop recognising the one case that must not be shown as an answer.
        // The raw Message is published for exactly this.
        //
        // Read BEFORE the generation, because a refusal comes back with empty content and Spring AI
        // produces no Generation at all for it — checking the answer first would fall into the
        // "nothing came back" branch and return a blank reply, which is the failure this guards.
        StopReason stopReason = nativeStopReason(response);
        Generation generation = response.getResult();

        if (generation == null) {
            // Anthropic returns empty content for a refusal, and Spring AI maps an empty content
            // list to a ChatResponse with no results AND no metadata — so the stop reason that
            // would say *why* is not reachable here, only on responses that had something in them.
            //
            // Refusal is much the likeliest cause, but this deliberately does not claim to know:
            // the sentence covers a declined request and a genuinely empty response equally, and
            // both leave the user needing the same thing. What matters is that neither surfaces as
            // a blank reply, which is what reading content[0] unconditionally used to produce.
            log.info("Anthropic returned nothing usable (likely a refusal; stop reason unavailable)");
            return new LlmTurn(List.of(new LlmBlock.Text(
                    "I can't help with that request. Try rephrasing it, or ask about your data directly.")),
                    false);
        }

        AssistantMessage answer = generation.getOutput();
        List<LlmBlock> blocks = new ArrayList<>();
        if (answer.getText() != null && !answer.getText().isBlank()) {
            blocks.add(new LlmBlock.Text(answer.getText()));
        }
        for (AssistantMessage.ToolCall call : answer.getToolCalls()) {
            blocks.add(new LlmBlock.ToolUse(call.id(), call.name(), toArgumentMap(call.arguments())));
        }

        if (stopReason == StopReason.MAX_TOKENS) {
            // The configured roof wins over the effort level by design, so this is the one place
            // the cost of that shows up. Without it a truncated answer looks like a poor answer.
            log.warn("Answer truncated at the {}-token ceiling (effort {}, agent {}). Raise the "
                            + "agent's maxOutputTokens, or ask at a lower effort.",
                    settings.maxOutputTokensFor(effort), effort, settings.agentId());
        }

        logUsage(response);
        return new LlmTurn(blocks, !answer.getToolCalls().isEmpty());
    }

    @Override
    public String providerId(AgentSettings settings) {
        return "anthropic/" + settings.model();
    }

    /**
     * The stop reason as Anthropic sent it, or null if the raw message is not available — in which
     * case nothing special is inferred, which is the safe reading.
     */
    private static StopReason nativeStopReason(ChatResponse response) {
        if (response.getMetadata() == null) {
            return null;
        }
        Object raw = response.getMetadata().get("anthropic-response");
        if (raw instanceof com.anthropic.models.messages.Message message) {
            return message.stopReason().orElse(null);
        }
        return null;
    }

    private List<Message> toSpringAiMessages(List<LlmMessage> messages) {
        // A tool result carries only the id it answers, but Spring AI's ToolResponse wants the
        // tool's name too, so remember what each id was called on the way past.
        Map<String, String> toolNamesById = new HashMap<>();
        List<Message> wire = new ArrayList<>();

        for (LlmMessage message : messages) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            StringBuilder text = new StringBuilder();

            for (LlmBlock block : message.blocks()) {
                switch (block) {
                    case LlmBlock.Text t -> text.append(t.text());
                    case LlmBlock.ToolUse call -> {
                        toolNamesById.put(call.id(), call.name());
                        calls.add(new AssistantMessage.ToolCall(call.id(), "function", call.name(),
                                json.writeValueAsString(call.args())));
                    }
                    // Spring AI's ToolResponse has no is_error flag, so a failed tool result would
                    // otherwise reach the model looking exactly like data it should reason over.
                    // Marking the text keeps the signal, in the one form that survives any wire:
                    // words the model reads. The stored transcript keeps the structured flag.
                    case LlmBlock.ToolResult result -> responses.add(
                            new ToolResponseMessage.ToolResponse(result.toolUseId(),
                                    toolNamesById.getOrDefault(result.toolUseId(), ""),
                                    result.isError() ? TOOL_ERROR_PREFIX + result.content()
                                            : result.content()));
                }
            }

            if (!responses.isEmpty()) {
                wire.add(ToolResponseMessage.builder().responses(responses).build());
            } else if (message.role() == LlmMessage.Role.USER) {
                wire.add(new UserMessage(text.toString()));
            } else {
                wire.add(AssistantMessage.builder().content(text.toString()).toolCalls(calls).build());
            }
        }
        return wire;
    }

    private Map<String, Object> toArgumentMap(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return json.readValue(arguments, ARGS);
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

    private void logUsage(ChatResponse response) {
        if (!log.isDebugEnabled() || response.getMetadata() == null) {
            return;
        }
        var usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        // Cache reads should be non-zero from the second turn onwards; if they stay at zero the
        // tool list or the system prompt is changing between requests.
        log.debug("Anthropic turn: input={} output={} native={}",
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getNativeUsage());
    }

    /**
     * A tool the model may be told about but that this client will never run.
     *
     * <p>Spring AI takes tools as callbacks even when it only needs their definitions, so this
     * supplies the definition and refuses execution. Reaching {@link #call} would mean
     * {@code ChatModel} had started executing tools itself, which would put a tool call outside
     * the policy check the loop performs — worth failing on rather than discovering later.
     */
    private final class DefinitionOnlyToolCallback implements ToolCallback {

        private final LlmToolDef def;

        private DefinitionOnlyToolCallback(LlmToolDef def) {
            this.def = def;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(def.name())
                    .description(def.description())
                    // Passed straight through as the api wrote it; nothing here interprets it.
                    .inputSchema(def.inputSchemaJson())
                    .build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("The model layer must not execute tools: " + def.name()
                    + ". The agent loop owns execution so that policy and permissions apply.");
        }
    }
}
