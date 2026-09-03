// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.dhconsole.chat.config.ChatProperties;
import ai.intellistream.dhconsole.chat.config.ChatSettings;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmClient;
import ai.intellistream.dhconsole.chat.llm.LlmClients;
import ai.intellistream.dhconsole.chat.llm.LlmMessage;
import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import ai.intellistream.dhconsole.chat.llm.LlmTurn;
import ai.intellistream.dhconsole.chat.mcp.McpBridge;
import ai.intellistream.dhconsole.chat.mcp.McpToolResult;
import ai.intellistream.dhconsole.chat.policy.ToolPolicy;
import ai.intellistream.dhconsole.chat.state.ChatConversation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The agent loop: ask the model, run whatever read-only tools it asks for, feed the results back,
 * repeat until it answers.
 *
 * <p>Runs entirely on the request thread, so {@code SecurityContextHolder} and
 * {@code RequestContextHolder} are intact and the caller's bearer token can simply be passed in.
 */
@Slf4j
@Service
public class ChatService {


    /** More buttons than this under one answer is noise rather than help. */
    private static final int MAX_VIEWS = 3;

    private final LlmClients backends;
    private final McpBridge mcp;
    private final ToolPolicy policy;
    private final ConsoleViews consoleViews;
    private final ChatPrompt prompt;
    private final ChatProperties properties;

    public ChatService(LlmClients backends, McpBridge mcp, ToolPolicy policy,
                       ConsoleViews consoleViews, ChatPrompt prompt, ChatProperties properties) {
        this.backends = backends;
        this.mcp = mcp;
        this.policy = policy;
        this.consoleViews = consoleViews;
        this.prompt = prompt;
        this.properties = properties;
    }

    /**
     * Run one user turn to completion.
     *
     * @param settings which model this tenant's chat runs on, resolved on the request thread
     * @param bearer the signed-in user's access token, captured on the request thread — every tool
     *               call is made as that user so datahub-api's ACLs and tenant routing apply
     * @param zone   the user's time zone, so relative periods resolve where they are
     * @param effort how hard to think about this message, chosen by the user in the panel. Applied
     *               to every iteration of this turn: a turn that earned a deep first pass has
     *               earned an equally deep pass over the tool results it just fetched.
     */
    public ChatReply send(ChatConversation conversation, ChatSettings settings, String bearer,
                          String userMessage, ZoneId zone, ChatEffort effort) {
        String systemPrompt = prompt.build(zone, settings.instructions());
        conversation.append(LlmMessage.user(userMessage));

        // Mutating tools are filtered out here, so the model is never even aware they exist and
        // cannot propose one. Console-owned navigation tools are appended: they are offered to the
        // model but run locally (see below), never forwarded to datahub-api.
        List<LlmToolDef> tools = new ArrayList<>(policy.selectAllowed(mcp.listTools(bearer)));
        tools.addAll(consoleViews.localToolDefs());
        Set<String> toolsUsed = new LinkedHashSet<>();
        // A LinkedHashSet so a filter the model repeats across iterations yields one button, in the
        // order the lookups happened.
        Set<ConsoleView> views = new LinkedHashSet<>();
        LlmClient llm = backends.forSettings(settings);

        for (int iteration = 0; iteration < settings.maxIterations(); iteration++) {
            LlmTurn turn = llm.send(settings, systemPrompt, tools, conversation.messages(), effort);
            conversation.append(LlmMessage.assistant(turn.blocks()));

            if (!turn.wantsTools()) {
                conversation.trimTo(properties.getMaxMessages());
                return new ChatReply(turn.text(), List.copyOf(toolsUsed), limited(views), false);
            }

            List<LlmBlock> results = new ArrayList<>();
            for (LlmBlock.ToolUse call : turn.toolUses()) {
                // A console-owned navigation tool: handled here, never sent to datahub-api. It is not
                // a data lookup, so it does not go in the consulted-tools trace.
                if (consoleViews.isLocalTool(call.name())) {
                    consoleViews.fromLocalCall(call).ifPresent(views::add);
                    results.add(new LlmBlock.ToolResult(call.id(),
                            "Done. The user now has a button to open the events page with that filter.",
                            false));
                    continue;
                }
                toolsUsed.add(call.name());
                LlmBlock.ToolResult result = execute(bearer, call);
                results.add(result);
                consoleViews.from(call, result).ifPresent(views::add);
            }
            // One message carrying every result from this assistant turn — splitting them would
            // teach the model to stop making parallel calls, and Anthropic rejects the shape.
            conversation.append(LlmMessage.toolResults(results));
        }

        log.warn("Chat turn hit the {}-iteration cap at effort {}", settings.maxIterations(), effort);
        conversation.trimTo(properties.getMaxMessages());
        return new ChatReply(lastAssistantText(conversation), List.copyOf(toolsUsed), limited(views), true);
    }

    private static List<ConsoleView> limited(Set<ConsoleView> views) {
        return views.stream().limit(MAX_VIEWS).toList();
    }

    private LlmBlock.ToolResult execute(String bearer, LlmBlock.ToolUse call) {
        // Belt and braces: the model was only offered read-only tools, but a tool call is model
        // output and this is the last point at which it can be refused.
        if (!policy.isReadOnly(call.name())) {
            log.warn("Refused a tool call outside the read-only allowlist: {}", call.name());
            return new LlmBlock.ToolResult(call.id(),
                    "This tool is not available in chat. Only read-only tools are permitted.", true);
        }
        McpToolResult result = mcp.callTool(bearer, call.name(), call.args());
        return new LlmBlock.ToolResult(call.id(), truncate(result.text()), result.isError());
    }

    private String truncate(String text) {
        int max = properties.getMaxToolResultChars();
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
