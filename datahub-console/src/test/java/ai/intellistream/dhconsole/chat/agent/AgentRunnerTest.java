// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.dhconsole.chat.config.AgentSettings;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmMessage;
import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import ai.intellistream.dhconsole.chat.llm.StubLlmClient;
import ai.intellistream.dhconsole.chat.mcp.McpBridge;
import ai.intellistream.dhconsole.chat.mcp.McpToolResult;
import ai.intellistream.dhconsole.chat.policy.ToolPolicy;
import ai.intellistream.dhconsole.chat.state.ChatConversation;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.anthropicAgent;
import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.readsEverything;
import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.readsNothing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunnerTest {

    private static final List<LlmToolDef> ADVERTISED = List.of(
            new LlmToolDef("dataset_list", "Browse datasets.", "{}"),
            new LlmToolDef("dataset_delete", "Delete a dataset.", "{}"));

    /**
     * The agent under test. Its allowlist names the tools these cases use but deliberately NOT
     * dataset_delete, which the api advertises — that gap is what the filtering assertions turn on.
     */
    private static final AgentSettings AGENT =
            anthropicAgent("dataset_list", "timeseries_search", "event_filter");

    private static final ExecutionIdentity USER = new ExecutionIdentity("tok", readsEverything());

    private McpBridge mcp;
    private StubLlmClient llm;
    private ChatConversation conversation;

    @BeforeEach
    void setUp() {
        mcp = mock(McpBridge.class);
        when(mcp.listTools(anyString())).thenReturn(ADVERTISED);
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn(McpToolResult.ok("{\"items\":[]}"));
        llm = new StubLlmClient();
        conversation = new ChatConversation();
    }

    /** The lambda is the whole point of {@code LlmClients}: no Vault, no credential, no network. */
    private AgentRunner runner() {
        return new AgentRunner(settings -> llm, mcp, new ToolPolicy(),
                new ConsoleViews(JsonMapper.builder().build()), new ChatPrompt());
    }

    private ChatReply send(String message, ChatEffort effort) {
        return runner().send(conversation, AGENT, USER, message, ZoneId.of("UTC"), effort);
    }

    private ChatReply send(AgentSettings agent, String message) {
        return runner().send(conversation, agent, USER, message, ZoneId.of("UTC"), ChatEffort.HIGH);
    }

    /** A copy of {@link #AGENT} with one budget changed, since the record is immutable. */
    private static AgentSettings withBudget(Integer maxOutputTokens, int maxIterations,
                                            int maxToolResultChars) {
        return new AgentSettings(AGENT.agentId(), AGENT.provider(), AGENT.apiKey(), AGENT.model(),
                AGENT.baseUrl(), AGENT.reasoningEffort(), AGENT.turnTimeout(), AGENT.instructions(),
                AGENT.toolAllowlist(), AGENT.defaultEffort(), maxOutputTokens, maxIterations,
                AGENT.maxMessages(), maxToolResultChars);
    }

    @Test
    void answersDirectlyWhenNoToolIsNeeded() {
        llm.thenText("You have three datasets.");

        ChatReply reply = send("how many datasets?", ChatEffort.HIGH);

        assertThat(reply.reply()).isEqualTo("You have three datasets.");
        assertThat(reply.toolsUsed()).isEmpty();
        assertThat(reply.truncated()).isFalse();
        verify(mcp, never()).callTool(anyString(), anyString(), any());
    }

    @Test
    void onlyToolsOnTheAgentsAllowlistAreOfferedToTheModel() {
        llm.thenText("hi");

        send("hello", ChatEffort.HIGH);

        // dataset_delete is advertised by the api but is not on this agent's allowlist, so it must
        // never reach the model and cannot be proposed in the first place. (open_events_view is a
        // console-local tool, added separately.)
        assertThat(llm.firstSent().tools()).extracting(LlmToolDef::name)
                .contains("dataset_list")
                .doesNotContain("dataset_delete");
    }

    @Test
    void offersTheConsoleNavigationToolAlongsideTheDataTools() {
        llm.thenText("hi");

        send("hello", ChatEffort.HIGH);

        assertThat(llm.firstSent().tools()).extracting(LlmToolDef::name).contains("open_events_view");
    }

    @Test
    void navigatesToEventsLocallyWithoutCallingTheApi() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "open_events_view", Map.of("type", "ALARM")));
        llm.thenText("Opening the alarms for you.");

        ChatReply reply = send("take me to those alarms", ChatEffort.HIGH);

        // Navigation is a console concern; it must never be forwarded to datahub-api.
        verify(mcp, never()).callTool(anyString(), eq("open_events_view"), any());
        assertThat(reply.views()).hasSize(1);
        assertThat(reply.views().getFirst().page()).isEqualTo("events");
        assertThat(reply.views().getFirst().filter()).containsEntry("type", "ALARM");
        // A navigation is not a data lookup, so it stays out of the consulted-tools trace.
        assertThat(reply.toolsUsed()).doesNotContain("open_events_view");
        assertThat(reply.reply()).isEqualTo("Opening the alarms for you.");
    }

    @Test
    void theChosenEffortAppliesToEveryIterationOfTheTurn() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenText("Here is what I found.");

        send("dig into this", ChatEffort.MAX);

        // Not just the opening call: a turn told to think its hardest has earned an equally hard
        // pass over the tool results it just fetched, which is where the answer is actually formed.
        assertThat(llm.sent()).hasSize(2);
        assertThat(llm.sent()).extracting(StubLlmClient.Sent::effort)
                .containsOnly(ChatEffort.MAX);
    }

    @Test
    void everyToolResultFromOneTurnGoesBackInASingleUserMessage() {
        llm.thenToolCalls(
                new LlmBlock.ToolUse("t1", "dataset_list", Map.of()),
                new LlmBlock.ToolUse("t2", "timeseries_search", Map.of("query", "pump")));
        llm.thenText("Here is what I found.");

        send("what do I have?", ChatEffort.HIGH);

        // Splitting results across messages trains the model out of parallel tool calls and is a
        // 400 on the Anthropic API.
        List<LlmMessage> resultMessages = conversation.messages().stream()
                .filter(m -> m.role() == LlmMessage.Role.USER && !m.isPlainUserTurn())
                .toList();
        assertThat(resultMessages).hasSize(1);
        assertThat(resultMessages.getFirst().blocks())
                .hasSize(2)
                .allSatisfy(b -> assertThat(b).isInstanceOf(LlmBlock.ToolResult.class));
        assertThat(resultMessages.getFirst().blocks())
                .extracting(b -> ((LlmBlock.ToolResult) b).toolUseId())
                .containsExactly("t1", "t2");
    }

    @Test
    void aToolOutsideTheAgentsAllowlistIsRefusedWithoutReachingTheApi() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_delete", Map.of("id", 1)));
        llm.thenText("I cannot do that.");

        ChatReply reply = send("delete dataset 1", ChatEffort.HIGH);

        verify(mcp, never()).callTool(anyString(), eq("dataset_delete"), any());
        LlmBlock.ToolResult refusal = (LlmBlock.ToolResult) conversation.messages().stream()
                .filter(m -> m.role() == LlmMessage.Role.USER && !m.isPlainUserTurn())
                .findFirst().orElseThrow().blocks().getFirst();
        assertThat(refusal.isError()).isTrue();
        assertThat(refusal.content()).contains("not available to this assistant");
        assertThat(reply.reply()).isEqualTo("I cannot do that.");
    }

    @Test
    void reportsWhichToolsWereConsulted() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenToolCalls(new LlmBlock.ToolUse("t2", "dataset_list", Map.of()));
        llm.thenText("Done.");

        ChatReply reply = send("check twice", ChatEffort.HIGH);

        // De-duplicated: the trace names what was consulted, not how many times.
        assertThat(reply.toolsUsed()).containsExactly("dataset_list");
    }

    @Test
    void offersToOpenAnEventLookupInTheConsole() {
        when(mcp.callTool(anyString(), eq("event_filter"), any()))
                .thenReturn(McpToolResult.ok("{\"returned\":5,\"events\":[]}"));
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "event_filter",
                Map.of("type", "ALARM", "start", "2026-08-01T00:00:00Z")));
        llm.thenText("Five alarms over the weekend.");

        ChatReply reply = send("what happened this weekend?", ChatEffort.HIGH);

        assertThat(reply.views()).hasSize(1);
        assertThat(reply.views().getFirst().page()).isEqualTo("events");
        assertThat(reply.views().getFirst().count()).isEqualTo(5);
        assertThat(reply.views().getFirst().filter()).containsEntry("type", "ALARM");
    }

    @Test
    void repeatingTheSameLookupStillOffersOneButton() {
        when(mcp.callTool(anyString(), eq("event_filter"), any()))
                .thenReturn(McpToolResult.ok("{\"returned\":5}"));
        var call = new LlmBlock.ToolUse("t1", "event_filter", Map.of("type", "ALARM"));
        llm.thenToolCalls(call);
        llm.thenToolCalls(new LlmBlock.ToolUse("t2", "event_filter", Map.of("type", "ALARM")));
        llm.thenText("Same five.");

        ChatReply reply = send("check twice", ChatEffort.HIGH);

        assertThat(reply.views()).hasSize(1);
    }

    @Test
    void aLookupWithNoMatchingPageOffersNothing() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenText("Three datasets.");

        assertThat(send("datasets?", ChatEffort.HIGH).views()).isEmpty();
    }

    @Test
    void stopsAtTheIterationCapAndSaysSo() {
        llm.alwaysAsksFor(new LlmBlock.ToolUse("t", "dataset_list", Map.of()));

        ChatReply reply = send(withBudget(null, 3, AGENT.maxToolResultChars()), "loop forever");

        assertThat(reply.truncated()).isTrue();
        verify(mcp, times(3)).callTool(anyString(), eq("dataset_list"), any());
    }

    @Test
    void oversizedToolResultsAreTruncatedBeforeEnteringTheTranscript() {
        when(mcp.callTool(anyString(), anyString(), any()))
                .thenReturn(McpToolResult.ok("x".repeat(5_000)));
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenText("Summarised.");

        send(withBudget(null, AGENT.maxIterations(), 50), "give me everything");

        LlmBlock.ToolResult result = (LlmBlock.ToolResult) conversation.messages().stream()
                .filter(m -> m.role() == LlmMessage.Role.USER && !m.isPlainUserTurn())
                .findFirst().orElseThrow().blocks().getFirst();
        assertThat(result.content()).hasSizeLessThan(200).endsWith("narrow the query to see more]");
    }

    @Test
    void aFailedToolCallIsFedBackToTheModelRatherThanAborting() {
        when(mcp.callTool(anyString(), anyString(), any()))
                .thenReturn(McpToolResult.error("Dataset 42 not found"));
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenText("That dataset does not exist.");

        ChatReply reply = send("show dataset 42", ChatEffort.HIGH);

        assertThat(reply.reply()).isEqualTo("That dataset does not exist.");
        LlmBlock.ToolResult result = (LlmBlock.ToolResult) conversation.messages().stream()
                .filter(m -> m.role() == LlmMessage.Role.USER && !m.isPlainUserTurn())
                .findFirst().orElseThrow().blocks().getFirst();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void aCallerWithNoGrantsIsOfferedNoDataToolsAtAll() {
        llm.thenText("You do not have access to any data yet.");

        runner().send(conversation, AGENT, new ExecutionIdentity("tok", readsNothing()),
                "what do I have?", ZoneId.of("UTC"), ChatEffort.HIGH);

        // The agent's allowlist says yes and the identity says no, so the answer is no. Only the
        // console-local navigation tools survive, and those reach nothing the api authorises.
        assertThat(llm.firstSent().tools()).extracting(LlmToolDef::name)
                .doesNotContain("dataset_list", "event_filter", "timeseries_search");
    }

    @Test
    void theConversationCarriesAcrossTurns() {
        llm.thenText("First answer.");
        AgentRunner runner = runner();
        runner.send(conversation, AGENT, USER, "first question", ZoneId.of("UTC"), ChatEffort.HIGH);

        llm.thenText("Second answer.");
        runner.send(conversation, AGENT, USER, "second question", ZoneId.of("UTC"), ChatEffort.HIGH);

        // The second request must include the first exchange, or the model has no memory.
        assertThat(llm.sent()).hasSize(2);
        assertThat(llm.sent().get(1).messages()).hasSize(3);
        assertThat(llm.sent().get(1).messages().getFirst().text()).isEqualTo("first question");
    }
}
