// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.chat.agent.AgentRunner;
import ai.intellistream.dhconsole.chat.agent.ChatReply;
import ai.intellistream.dhconsole.chat.agent.ConsoleViews;
import ai.intellistream.dhconsole.chat.agent.ExecutionIdentity;
import ai.intellistream.dhconsole.chat.config.AgentSettings;
import ai.intellistream.dhconsole.chat.config.AgentSettingsResolver;
import ai.intellistream.dhconsole.chat.config.ChatProperties;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmMessage;
import ai.intellistream.dhconsole.chat.mcp.McpException;
import ai.intellistream.dhconsole.chat.state.ChatConversation;
import ai.intellistream.dhconsole.security.AccessTokens;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.anthropicAgent;
import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.readsEverything;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc, matching how datahub-api tests its controllers — booting the context would
 * drag in Vault and the OAuth2 client for no benefit here.
 */
class ChatApiControllerTest {

    private static final String CONVERSATION_ATTRIBUTE = "datahub.chat.conversation";

    private AgentRunner agentRunner;
    private AgentSettingsResolver settingsResolver;
    private ChatProperties properties;
    private MockMvc mockMvc;

    /** The resolved agent the controller will hand to the loop, unless a test replaces it. */
    private void agentAnswers(AgentSettings settings) {
        when(settingsResolver.forConsoleAgent()).thenReturn(settings);
    }

    /** A copy of the fixture agent with a different starting effort or turn budget. */
    private static AgentSettings agentWith(ChatEffort defaultEffort, java.time.Duration turnTimeout) {
        AgentSettings base = anthropicAgent("dataset_list");
        return new AgentSettings(base.agentId(), base.provider(), base.apiKey(), base.model(),
                base.baseUrl(), base.reasoningEffort(), turnTimeout, base.instructions(),
                base.toolAllowlist(), defaultEffort, base.maxOutputTokens(), base.maxIterations(),
                base.maxMessages(), base.maxToolResultChars());
    }

    @BeforeEach
    void setUp() {
        agentRunner = mock(AgentRunner.class);
        settingsResolver = mock(AgentSettingsResolver.class);
        agentAnswers(anthropicAgent("dataset_list"));

        DatahubApi datahubApi = mock(DatahubApi.class);
        when(datahubApi.getCallerPermissions()).thenReturn(readsEverything());

        AccessTokens accessTokens = mock(AccessTokens.class);
        when(accessTokens.token()).thenReturn("user-token");

        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("chat.error.generic", Locale.ENGLISH, "Something went wrong.");
        messages.addMessage("chat.error.session.expired", Locale.ENGLISH, "Your session expired.");
        messages.addMessage("chat.error.api.unreachable", Locale.ENGLISH, "API unreachable.");
        messages.setUseCodeAsDefaultMessage(true);

        ConsoleViews consoleViews = new ConsoleViews(JsonMapper.builder().build());
        properties = new ChatProperties();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatApiController(agentRunner, settingsResolver, datahubApi,
                        accessTokens, messages, consoleViews, properties))
                .build();
    }

    @Test
    void returnsTheReplyAndTheToolTrace() throws Exception {
        when(agentRunner.send(any(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ChatReply("You have 3 datasets.", List.of("dataset_list"), List.of(), false));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"how many datasets?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("You have 3 datasets."))
                .andExpect(jsonPath("$.toolsUsed[0]").value("dataset_list"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void forwardsTheSignedInUsersTokenToTheLoop() throws Exception {
        when(agentRunner.send(any(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ChatReply("ok", List.of(), List.of(), false));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        // Not a service account: every tool call downstream runs as this user. The token now
        // travels inside the execution identity, which is what an autonomous agent would swap.
        verify(agentRunner).send(any(),
                any(),
                argThat((ExecutionIdentity identity) -> "user-token".equals(identity.bearer())),
                eq("hi"), any(), any());
    }

    @Test
    void forwardsTheEffortTheUserPickedForThisMessage() throws Exception {
        when(agentRunner.send(any(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ChatReply("ok", List.of(), List.of(), false));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"dig into this\",\"effort\":\"max\"}"))
                .andExpect(status().isOk());

        verify(agentRunner).send(any(), any(), any(), eq("dig into this"), any(), eq(ChatEffort.MAX));
    }

    @Test
    void anAbsentOrUnusableEffortFallsBackToTheAgentsDefault() throws Exception {
        agentAnswers(agentWith(ChatEffort.MEDIUM, java.time.Duration.ofMinutes(4)));
        when(agentRunner.send(any(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ChatReply("ok", List.of(), List.of(), false));

        // A panel cached from before the picker shipped sends no effort at all; one cached across a
        // rename sends a level that no longer exists. Neither should cost the user their answer.
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"effort\":\"ludicrous\"}"))
                .andExpect(status().isOk());

        verify(agentRunner, times(2))
                .send(any(), any(), any(), anyString(), any(), eq(ChatEffort.MEDIUM));
    }

    @Test
    void tellsThePanelWhichEffortToStartOn() throws Exception {
        agentAnswers(agentWith(ChatEffort.XHIGH, java.time.Duration.ofMinutes(4)));

        mockMvc.perform(get("/api/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultEffort").value("xhigh"));
    }

    @Test
    void tellsThePanelHowLongToWaitForATurn() throws Exception {
        // The panel cannot hardcode this: a self-hosted model on CPU needs minutes per turn where a
        // hosted one needs seconds, and the wrong number shows up as a dropped connection rather
        // than as anything the server logs.
        agentAnswers(agentWith(ChatEffort.HIGH, java.time.Duration.ofMinutes(10)));

        mockMvc.perform(get("/api/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnTimeoutMs").value(600000));
    }

    @Test
    void keepsTheTranscriptInTheSessionAcrossRequests() throws Exception {
        when(agentRunner.send(any(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ChatReply("ok", List.of(), List.of(), false));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"first\"}"))
                .andExpect(status().isOk());

        Object stored = session.getAttribute(CONVERSATION_ATTRIBUTE);
        assertThat(stored).isInstanceOf(ChatConversation.class);

        mockMvc.perform(post("/api/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"second\"}"))
                .andExpect(status().isOk());

        // Same instance reused, so the model sees the earlier turns.
        assertThat(session.getAttribute(CONVERSATION_ATTRIBUTE)).isSameAs(stored);
    }

    @Test
    void anExpiredTokenIsReportedAsUnauthorisedNotAsAServerError() throws Exception {
        when(agentRunner.send(any(), any(), any(), anyString(), any(), any()))
                .thenThrow(new McpException("401 from api", true));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Your session expired."));
    }

    @Test
    void anApiFailureIsReportedAsBadGateway() throws Exception {
        when(agentRunner.send(any(), any(), any(), anyString(), any(), any()))
                .thenThrow(new McpException("connection refused", false));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("API unreachable."));
    }

    @Test
    void historyReturnsProseTurnsAndSkipsToolPlumbing() throws Exception {
        ChatConversation conversation = new ChatConversation();
        conversation.append(LlmMessage.user("how many datasets?"));
        conversation.append(LlmMessage.assistant(List.of(
                new LlmBlock.Text("Let me check."),
                new LlmBlock.ToolUse("t1", "dataset_list", Map.of()))));
        conversation.append(LlmMessage.toolResults(List.of(
                new LlmBlock.ToolResult("t1", "{\"items\":[]}", false))));
        conversation.append(LlmMessage.assistant(List.of(new LlmBlock.Text("You have 3."))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONVERSATION_ATTRIBUTE, conversation);

        // Only the user turn and the final answer appear: the tool_use/tool_result plumbing and the
        // interim "Let me check." narration are both left out, matching the live panel.
        mockMvc.perform(get("/api/chat").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].text").value("how many datasets?"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].text").value("You have 3."));
    }

    @Test
    void historyRebuildsTheEventsButtonFromTheStoredCall() throws Exception {
        ChatConversation conversation = new ChatConversation();
        conversation.append(LlmMessage.user("what happened this weekend?"));
        conversation.append(LlmMessage.assistant(List.of(
                new LlmBlock.Text("Checking."),
                new LlmBlock.ToolUse("t1", "event_filter", Map.of("type", "ALARM")))));
        conversation.append(LlmMessage.toolResults(List.of(
                new LlmBlock.ToolResult("t1", "{\"returned\":5}", false))));
        conversation.append(LlmMessage.assistant(List.of(new LlmBlock.Text("Five alarms."))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONVERSATION_ATTRIBUTE, conversation);

        // The interim "Checking." turn is dropped; the button is re-derived from the stored call and
        // attached to the exchange's final answer.
        mockMvc.perform(get("/api/chat").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].text").value("Five alarms."))
                .andExpect(jsonPath("$.messages[1].views[0].page").value("events"))
                .andExpect(jsonPath("$.messages[1].views[0].count").value(5))
                .andExpect(jsonPath("$.messages[1].views[0].filter.type").value("ALARM"));
    }

    @Test
    void historyRebuildsTheButtonFromAnOpenEventsViewCall() throws Exception {
        ChatConversation conversation = new ChatConversation();
        conversation.append(LlmMessage.user("take me to those alarms"));
        conversation.append(LlmMessage.assistant(List.of(
                new LlmBlock.ToolUse("t1", "open_events_view", Map.of("type", "ALARM")))));
        conversation.append(LlmMessage.toolResults(List.of(
                new LlmBlock.ToolResult("t1", "Done.", false))));
        conversation.append(LlmMessage.assistant(List.of(new LlmBlock.Text("Opening the alarms."))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONVERSATION_ATTRIBUTE, conversation);

        mockMvc.perform(get("/api/chat").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].text").value("Opening the alarms."))
                .andExpect(jsonPath("$.messages[1].views[0].page").value("events"))
                .andExpect(jsonPath("$.messages[1].views[0].filter.type").value("ALARM"));
    }

    @Test
    void historyIsEmptyForAFreshSession() throws Exception {
        mockMvc.perform(get("/api/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(0));
    }

    @Test
    void resetDropsTheTranscript() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONVERSATION_ATTRIBUTE, new ChatConversation());

        mockMvc.perform(post("/api/chat/reset").session(session))
                .andExpect(status().isNoContent());

        assertThat(session.getAttribute(CONVERSATION_ATTRIBUTE)).isNull();
    }
}
