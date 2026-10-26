// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.agents;

import ai.intellistream.datahub.flow.fake.FakeMcp;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The picker's list: both servers' tools with their hints, as the caller, behind the read grant. */
class AgentToolsControllerTest {

    private final FlowService flows = mock(FlowService.class);
    private FakeMcp api;
    private FakeMcp analysis;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        api = new FakeMcp("datahub-api");
        api.tool("event_get", true, false);
        api.tool("event_delete", false, true);
        api.tool("thing_do", null, null);
        analysis = new FakeMcp("datahub-analysis");
        analysis.tool("analysis_related_series", true, false);
        JsonMapper json = JsonMapper.builder().build();
        HttpClient http = HttpClient.newHttpClient();
        List<StatelessMcpClient> servers = List.of(new StatelessMcpClient("datahub-api", api.url(), http, json),
                new StatelessMcpClient("datahub-analysis", analysis.url(), http, json));
        mvc = MockMvcBuilders.standaloneSetup(new AgentToolsController(flows, servers))
                .setControllerAdvice(new FlowProblems()).build();
        Jwt jwt = Jwt.withTokenValue("caller-token").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        api.close();
        analysis.close();
    }

    @Test
    void theToolsOfEveryServerComeWithTheirHintsAskedAsTheCaller() throws Exception {
        mvc.perform(get("/agents/tools")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tools.length()").value(4))
                .andExpect(jsonPath("$.tools[0].server").value("datahub-api"))
                .andExpect(jsonPath("$.tools[0].name").value("event_get"))
                .andExpect(jsonPath("$.tools[0].description").value("event get"))
                .andExpect(jsonPath("$.tools[0].readOnly").value(true))
                .andExpect(jsonPath("$.tools[0].destructive").value(false))
                .andExpect(jsonPath("$.tools[1].destructive").value(true))
                .andExpect(jsonPath("$.tools[2].readOnly").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tools[3].server").value("datahub-analysis"))
                .andExpect(jsonPath("$.tools[3].name").value("analysis_related_series"));
        assertThat(api.listCalls).isEqualTo(1);
        assertThat(analysis.listCalls).isEqualTo(1);
    }

    @Test
    void itTakesTheReadGrant() throws Exception {
        doThrow(FlowProblems.forbidden("Seeing flows takes the /flows/read grant.")).when(flows).requireRead();
        mvc.perform(get("/agents/tools")).andExpect(status().isForbidden());
        assertThat(api.listCalls).isZero();
    }

    @Test
    void aServerThatDoesNotAnswerIsA503() throws Exception {
        analysis.close();
        mvc.perform(get("/agents/tools")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/tools-unavailable"));
    }
}
