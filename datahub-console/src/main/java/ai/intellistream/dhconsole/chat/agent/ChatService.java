// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.datahub.llm.LlmBlock;
import ai.intellistream.datahub.llm.LlmClient;
import ai.intellistream.datahub.llm.LlmClients;
import ai.intellistream.datahub.llm.LlmEffort;
import ai.intellistream.datahub.llm.LlmMessage;
import ai.intellistream.datahub.llm.LlmSettings;
import ai.intellistream.datahub.llm.LlmToolDef;
import ai.intellistream.datahub.llm.LlmTurn;
import ai.intellistream.datahub.llm.ToolLoop;
import ai.intellistream.datahub.llm.mcp.McpToolResult;
import ai.intellistream.dhconsole.chat.config.ChatProperties;
import ai.intellistream.dhconsole.chat.mcp.McpBridge;
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
 * One chat turn: {@link ToolLoop} with the console's own pieces around it — the read-only tool
 * policy, the signed-in user's token on every tool call, the navigation tools that run locally,
 * and the transcript in the HTTP session.
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
    public ChatReply send(ChatConversation conversation, LlmSettings settings, String bearer,
                          String userMessage, ZoneId zone, LlmEffort effort) {
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

        // A turn is one HTTP request that can legitimately run for minutes, so it logs as it goes.
        // Without this a slow model and a hung one look identical: silence either way.
        var timing = new ToolLoop.Listener() {
            long modelMs;
            long toolMs;

            @Override
            public void modelCalled(int iteration, LlmTurn turn, long millis) {
                modelMs += millis;
                log.info("Chat turn: model call {}/{} took {} ms, {}", iteration,
                        settings.maxIterations(), millis,
                        turn.wantsTools() ? "wants " + toolNames(turn) : "answering");
            }

            @Override
            public void toolCalled(LlmBlock.ToolUse call, LlmBlock.ToolResult result, long millis) {
                toolMs += millis;
                log.debug("Chat turn: tool {} took {} ms{}", call.name(), millis,
                        result.isError() ? " (error)" : "");
            }
        };
        ToolLoop.ToolExecutor executor = call -> {
            // A console-owned navigation tool: handled here, never sent to datahub-api. It is not
            // a data lookup, so it does not go in the consulted-tools trace.
            if (consoleViews.isLocalTool(call.name())) {
                consoleViews.fromLocalCall(call).ifPresent(views::add);
                return new LlmBlock.ToolResult(call.id(),
                        "Done. The user now has a button to open the events page with that filter.",
                        false);
            }
            toolsUsed.add(call.name());
            LlmBlock.ToolResult result = execute(bearer, call);
            consoleViews.from(call, result).ifPresent(views::add);
            return result;
        };

        ToolLoop.Outcome outcome = ToolLoop.run(llm, settings, systemPrompt, tools, conversation,
                effort, executor, timing, () -> false);

        conversation.trimTo(properties.getMaxMessages());
        boolean truncated = outcome.ended() == ToolLoop.Ended.ITERATION_CAP;
        if (truncated) {
            log.warn("Chat turn hit the {}-iteration cap at effort {}", settings.maxIterations(), effort);
        }
        // Where a turn's wall-clock and tokens went, which is the first question a slow one raises.
        log.info("Chat turn {}: {} model call(s) {} ms, tools {} ms, {} tokens in / {} out",
                describe(outcome.ended()), outcome.iterations(), timing.modelMs, timing.toolMs,
                outcome.usage().inputTokens(), outcome.usage().outputTokens());
        return new ChatReply(outcome.lastText(), List.copyOf(toolsUsed), limited(views), truncated);
    }

    private static String describe(ToolLoop.Ended ended) {
        return switch (ended) {
            case ANSWERED -> "answered";
            case MAX_TOKENS -> "answered, cut off at the output ceiling";
            case ITERATION_CAP -> "truncated at the iteration cap";
            case STOPPED -> "stopped";
        };
    }

    private static String toolNames(LlmTurn turn) {
        return turn.toolUses().stream().map(LlmBlock.ToolUse::name).toList().toString();
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
        // A single timeseries_fetch_datapoints can return far more than the conversation needs,
        // and the transcript is serialised into the session on every request.
        return new LlmBlock.ToolResult(call.id(),
                ToolLoop.truncate(result.text(), properties.getMaxToolResultChars()), result.isError());
    }
}
