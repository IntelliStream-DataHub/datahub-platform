// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.dhconsole.chat.config.AgentSettings;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.chat.llm.LlmClients;
import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmClient;
import ai.intellistream.dhconsole.chat.llm.LlmMessage;
import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import ai.intellistream.dhconsole.chat.llm.LlmTurn;
import ai.intellistream.dhconsole.chat.mcp.McpBridge;
import ai.intellistream.dhconsole.chat.mcp.McpToolResult;
import ai.intellistream.dhconsole.chat.policy.ToolPolicy;
import ai.intellistream.dhconsole.chat.state.ChatConversation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The agent loop: ask the model, run whatever tools it asks for and is allowed, feed the results
 * back, repeat until it answers.
 *
 * <p>Was {@code ChatService}, and renamed because it no longer serves one hard-wired chatbot: what
 * it runs is whichever {@link AgentSettings} it is handed, as whichever {@link ExecutionIdentity}
 * it is handed. The console's assistant is the first caller, not the only possible one.
 *
 * <p>Everything it needs arrives as parameters rather than injected state. That is what lets one
 * instance serve concurrent turns for different tenants, on different models, with different tool
 * lists — and what would let a caller other than an HTTP request drive it. It still runs entirely
 * on the calling thread, so {@code SecurityContextHolder} and {@code RequestContextHolder} are
 * intact for the console's use of it.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class AgentRunner {

    /** More buttons than this under one answer is noise rather than help. */
    private static final int MAX_VIEWS = 3;

    private final LlmClients backends;
    private final McpBridge mcp;
    private final ToolPolicy policy;
    private final ConsoleViews consoleViews;
    private final ChatPrompt prompt;

    public AgentRunner(LlmClients backends, McpBridge mcp, ToolPolicy policy,
                       ConsoleViews consoleViews, ChatPrompt prompt) {
        this.backends = backends;
        this.mcp = mcp;
        this.policy = policy;
        this.consoleViews = consoleViews;
        this.prompt = prompt;
    }

    /**
     * Run one turn to completion.
     *
     * @param settings the resolved agent: which model, what instructions, which tools it may use,
     *                 and what it may spend
     * @param identity who this turn runs as. Every tool call is made with its token, so
     *                 datahub-api's ACLs and tenant routing apply exactly as they do to the REST
     *                 UI, and its permissions narrow what the model is offered in the first place
     * @param zone     the user's time zone, so relative periods resolve where they are
     * @param effort   how hard to think about this message. Applied to every iteration of this
     *                 turn: a turn that earned a deep first pass has earned an equally deep pass
     *                 over the tool results it just fetched
     */
    public ChatReply send(ChatConversation conversation, AgentSettings settings,
                          ExecutionIdentity identity, String userMessage, ZoneId zone,
                          ChatEffort effort) {
        String systemPrompt = prompt.build(zone, settings.instructions());
        conversation.append(LlmMessage.user(userMessage));

        // The three-way narrowing happens here, once: what the servers advertise, intersected with
        // the agent's explicit list and with what this identity may do. Anything filtered out the
        // model is never even aware of, so it cannot propose it. Console-owned navigation tools
        // are appended afterwards: they are offered to the model but run locally, never forwarded
        // to datahub-api, so they are not the api's to authorise.
        List<LlmToolDef> tools = new ArrayList<>(
                policy.selectAllowed(mcp.listTools(identity.bearer()), settings, identity.permissions()));
        tools.addAll(consoleViews.localToolDefs());

        LlmClient llm = backends.forSettings(settings);
        Set<String> toolsUsed = new LinkedHashSet<>();
        // A LinkedHashSet so a filter the model repeats across iterations yields one button, in the
        // order the lookups happened.
        Set<ConsoleView> views = new LinkedHashSet<>();

        for (int iteration = 0; iteration < settings.maxIterations(); iteration++) {
            LlmTurn turn = llm.send(settings, systemPrompt, tools, conversation.messages(), effort);
            conversation.append(LlmMessage.assistant(turn.blocks()));

            if (!turn.wantsTools()) {
                conversation.trimTo(settings.maxMessages());
                return new ChatReply(turn.text(), List.copyOf(toolsUsed), limited(views), false);
            }

            List<LlmBlock> results = new ArrayList<>();
            for (LlmBlock.ToolUse call : turn.toolUses()) {
                // A console-owned navigation tool: handled here, never sent to datahub-api. It is
                // not a data lookup, so it does not go in the consulted-tools trace.
                if (consoleViews.isLocalTool(call.name())) {
                    consoleViews.fromLocalCall(call).ifPresent(views::add);
                    results.add(new LlmBlock.ToolResult(call.id(),
                            "Done. The user now has a button to open the events page with that filter.",
                            false));
                    continue;
                }
                toolsUsed.add(call.name());
                LlmBlock.ToolResult result = execute(settings, identity, call);
                results.add(result);
                consoleViews.from(call, result).ifPresent(views::add);
            }
            // One message carrying every result from this assistant turn — splitting them would
            // teach the model to stop making parallel calls, and Anthropic rejects the shape.
            conversation.append(LlmMessage.toolResults(results));
        }

        log.warn("Agent {} hit the {}-iteration cap at effort {}", settings.agentId(),
                settings.maxIterations(), effort);
        conversation.trimTo(settings.maxMessages());
        return new ChatReply(lastAssistantText(conversation), List.copyOf(toolsUsed), limited(views), true);
    }

    private static List<ConsoleView> limited(Set<ConsoleView> views) {
        return views.stream().limit(MAX_VIEWS).toList();
    }

    private LlmBlock.ToolResult execute(AgentSettings settings, ExecutionIdentity identity,
                                        LlmBlock.ToolUse call) {
        // Belt and braces: the model was only offered tools that passed the same check, but a tool
        // call is model output and this is the last point at which it can be refused.
        if (!policy.isAllowed(call.name(), settings, identity.permissions())) {
            log.warn("Agent {} refused a tool call outside its allowlist: {}",
                    settings.agentId(), call.name());
            return new LlmBlock.ToolResult(call.id(),
                    "This tool is not available to this assistant.", true);
        }
        McpToolResult result = mcp.callTool(identity.bearer(), call.name(), call.args());
        return new LlmBlock.ToolResult(call.id(), truncate(settings, result.text()), result.isError());
    }

    private static String truncate(AgentSettings settings, String text) {
        int max = settings.maxToolResultChars();
        if (text.length() <= max) {
            return text;
        }
        // Tell the model it was cut off, so it narrows the query rather than reasoning over a
        // half-object it thinks is complete.
        return text.substring(0, max) + "\n…[truncated; narrow the query to see more]";
    }

    private static String lastAssistantText(ChatConversation conversation) {
        List<LlmMessage> messages = conversation.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message.role() == LlmMessage.Role.ASSISTANT && !message.text().isBlank()) {
                return message.text();
            }
        }
        return "";
    }
}
