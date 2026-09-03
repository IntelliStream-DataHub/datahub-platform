// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.dhconsole.chat.config.ChatProperties;
import ai.intellistream.dhconsole.chat.config.ChatSettings;
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

import static ai.intellistream.dhconsole.chat.config.ChatSettingsFixture.anthropic;
import static ai.intellistream.dhconsole.chat.config.ChatSettingsFixture.anthropicWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final List<LlmToolDef> ADVERTISED = List.of(
            new LlmToolDef("dataset_list", "Browse datasets.", "{}"),
            new LlmToolDef("dataset_delete", "Delete a dataset.", "{}"));

    private McpBridge mcp;
    private StubLlmClient llm;
    private ChatProperties properties;
    private ChatConversation conversation;

    @BeforeEach
    void setUp() {
        mcp = mock(McpBridge.class);
        when(mcp.listTools(anyString())).thenReturn(ADVERTISED);
        when(mcp.callTool(anyString(), anyString(), any())).thenReturn(McpToolResult.ok("{\"items\":[]}"));
        llm = new StubLlmClient();
        properties = new ChatProperties();
        conversation = new ChatConversation();
    }

    private static final ChatSettings SETTINGS = anthropic();

    /** The lambda is the point of {@code LlmClients}: no Vault, no credential, no network. */
    private ChatService service() {
        return new ChatService(settings -> llm, mcp, new ToolPolicy(),
                new ConsoleViews(JsonMapper.builder().build()), new ChatPrompt(), properties);
    }

    @Test
    void answersDirectlyWhenNoToolIsNeeded() {
        llm.thenText("You have three datasets.");

        ChatReply reply = service().send(conversation, SETTINGS, "tok", "how many datasets?", ZoneId.of("UTC"), ChatEffort.HIGH);

        assertThat(reply.reply()).isEqualTo("You have three datasets.");
        assertThat(reply.toolsUsed()).isEmpty();
        assertThat(reply.truncated()).isFalse();
        verify(mcp, never()).callTool(anyString(), anyString(), any());
    }

    @Test
    void onlyReadOnlyToolsAreOfferedToTheModel() {
        llm.thenText("hi");

        service().send(conversation, SETTINGS, "tok", "hello", ZoneId.of("UTC"), ChatEffort.HIGH);

        // dataset_delete is advertised by the api but must never reach the model, so it cannot be
        // proposed in the first place. (open_events_view is a console-local tool, added separately.)
        assertThat(llm.firstSent().tools()).extracting(LlmToolDef::name)
                .contains("dataset_list")
                .doesNotContain("dataset_delete");
    }

    @Test
    void offersTheConsoleNavigationToolAlongsideTheDataTools() {
        llm.thenText("hi");

        service().send(conversation, SETTINGS, "tok", "hello", ZoneId.of("UTC"), ChatEffort.HIGH);

        assertThat(llm.firstSent().tools()).extracting(LlmToolDef::name).contains("open_events_view");
    }

    @Test
    void navigatesToEventsLocallyWithoutCallingTheApi() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "open_events_view", Map.of("type", "ALARM")));
        llm.thenText("Opening the alarms for you.");

        ChatReply reply = service().send(conversation, SETTINGS, "tok", "take me to those alarms", ZoneId.of("UTC"), ChatEffort.HIGH);

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

        service().send(conversation, SETTINGS, "tok", "dig into this", ZoneId.of("UTC"), ChatEffort.MAX);

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

        service().send(conversation, SETTINGS, "tok", "what do I have?", ZoneId.of("UTC"), ChatEffort.HIGH);

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
    void aToolOutsideTheAllowlistIsRefusedWithoutReachingTheApi() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_delete", Map.of("id", 1)));
        llm.thenText("I cannot do that.");

        ChatReply reply = service().send(conversation, SETTINGS, "tok", "delete dataset 1", ZoneId.of("UTC"), ChatEffort.HIGH);

        verify(mcp, never()).callTool(anyString(), eq("dataset_delete"), any());
        LlmBlock.ToolResult refusal = (LlmBlock.ToolResult) conversation.messages().stream()
                .filter(m -> m.role() == LlmMessage.Role.USER && !m.isPlainUserTurn())
                .findFirst().orElseThrow().blocks().getFirst();
        assertThat(refusal.isError()).isTrue();
        assertThat(refusal.content()).contains("not available in chat");
        assertThat(reply.reply()).isEqualTo("I cannot do that.");
    }

    @Test
    void reportsWhichToolsWereConsulted() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenToolCalls(new LlmBlock.ToolUse("t2", "dataset_list", Map.of()));
        llm.thenText("Done.");

        ChatReply reply = service().send(conversation, SETTINGS, "tok", "check twice", ZoneId.of("UTC"), ChatEffort.HIGH);

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

        ChatReply reply = service().send(conversation, SETTINGS, "tok", "what happened this weekend?", ZoneId.of("UTC"), ChatEffort.HIGH);

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

        ChatReply reply = service().send(conversation, SETTINGS, "tok", "check twice", ZoneId.of("UTC"), ChatEffort.HIGH);

        assertThat(reply.views()).hasSize(1);
    }

    @Test
    void aLookupWithNoMatchingPageOffersNothing() {
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenText("Three datasets.");

        assertThat(service().send(conversation, SETTINGS, "tok", "datasets?", ZoneId.of("UTC"), ChatEffort.HIGH).views()).isEmpty();
    }

    @Test
    void stopsAtTheIterationCapAndSaysSo() {
        // The cap is the tenant's, off the settings, not the deployment's.
        llm.alwaysAsksFor(new LlmBlock.ToolUse("t", "dataset_list", Map.of()));

        ChatReply reply = service().send(conversation, anthropicWith(3, null), "tok", "loop forever",
                ZoneId.of("UTC"), ChatEffort.HIGH);

        assertThat(reply.truncated()).isTrue();
        verify(mcp, times(3)).callTool(anyString(), eq("dataset_list"), any());
    }

    @Test
    void oversizedToolResultsAreTruncatedBeforeEnteringTheTranscript() {
        properties.setMaxToolResultChars(50);
        when(mcp.callTool(anyString(), anyString(), any()))
                .thenReturn(McpToolResult.ok("x".repeat(5_000)));
        llm.thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()));
        llm.thenText("Summarised.");

        service().send(conversation, SETTINGS, "tok", "give me everything", ZoneId.of("UTC"), ChatEffort.HIGH);

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

        ChatReply reply = service().send(conversation, SETTINGS, "tok", "show dataset 42", ZoneId.of("UTC"), ChatEffort.HIGH);

        assertThat(reply.reply()).isEqualTo("That dataset does not exist.");
        LlmBlock.ToolResult result = (LlmBlock.ToolResult) conversation.messages().stream()
                .filter(m -> m.role() == LlmMessage.Role.USER && !m.isPlainUserTurn())
                .findFirst().orElseThrow().blocks().getFirst();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void theConversationCarriesAcrossTurns() {
        llm.thenText("First answer.");
        ChatService service = service();
        service.send(conversation, SETTINGS, "tok", "first question", ZoneId.of("UTC"), ChatEffort.HIGH);

        llm.thenText("Second answer.");
        service.send(conversation, SETTINGS, "tok", "second question", ZoneId.of("UTC"), ChatEffort.HIGH);

        // The second request must include the first exchange, or the model has no memory.
        assertThat(llm.sent()).hasSize(2);
        assertThat(llm.sent().get(1).messages()).hasSize(3);
        assertThat(llm.sent().get(1).messages().getFirst().text()).isEqualTo("first question");
    }
}
