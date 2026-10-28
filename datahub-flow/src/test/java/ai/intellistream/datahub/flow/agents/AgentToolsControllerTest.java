// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.agents;

import ai.intellistream.datahub.flow.fake.FakeMcp;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
    private final TenantConfigService tenants = mock(TenantConfigService.class);
    private final TenantConfigWriter tenantConfig = mock(TenantConfigWriter.class);
    private final Map<String, String> flowSettings = new HashMap<>();
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
        ExternalMcp external = new ExternalMcp(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), json, Duration.ofSeconds(5), true);
        Tenant tenant = new Tenant();
        tenant.setOrganizationName("acme");
        when(tenants.getConfig("t")).thenReturn(tenant);
        when(tenantConfig.current("acme")).thenAnswer(invocation -> Map.copyOf(flowSettings));
        TenantContext.setTenantId("t");
        mvc = MockMvcBuilders.standaloneSetup(new AgentToolsController(flows, servers, external, tenants, tenantConfig))
                .setControllerAdvice(new FlowProblems()).build();
        Jwt jwt = Jwt.withTokenValue("caller-token").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
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
                .andExpect(jsonPath("$.tools[3].name").value("analysis_related_series"))
                .andExpect(jsonPath("$.tools[3].external").value(false))
                .andExpect(jsonPath("$.unavailable.length()").value(0));
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

    @Test
    void aRegisteredServersToolsAreListedPrefixedAndMarkedAndItsSessionClosed() throws Exception {
        try (FakeMcp crm = new FakeMcp("crm", true)) {
            crm.tool("ticket_get", true, false);
            flowSettings.putAll(TenantFlowSection.putMcpServer("crm", crm.url(), "X-Api-Key", "k").apply(flowSettings));

            mvc.perform(get("/agents/tools")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.tools.length()").value(5))
                    .andExpect(jsonPath("$.tools[4].server").value("crm"))
                    .andExpect(jsonPath("$.tools[4].name").value("crm__ticket_get"))
                    .andExpect(jsonPath("$.tools[4].readOnly").value(true))
                    .andExpect(jsonPath("$.tools[4].external").value(true))
                    .andExpect(jsonPath("$.unavailable.length()").value(0));
            assertThat(crm.sessions).isEqualTo(1);
            assertThat(crm.deletes).isEqualTo(1);
        }
    }

    @Test
    void aRegisteredServerThatDoesNotAnswerIsReportedBesideTheRest() throws Exception {
        FakeMcp crm = new FakeMcp("crm", true);
        flowSettings.putAll(TenantFlowSection.putMcpServer("crm", crm.url(), "Authorization", "Bearer k").apply(flowSettings));
        crm.close();

        mvc.perform(get("/agents/tools")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tools.length()").value(4))
                .andExpect(jsonPath("$.unavailable.length()").value(1))
                .andExpect(jsonPath("$.unavailable[0].server").value("crm"))
                .andExpect(jsonPath("$.unavailable[0].error").value(org.hamcrest.Matchers.containsString("crm")));
    }
}
