// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp;

import ai.intellistream.datahub.analysis.compute.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /mcp} through the real stateless transport: it must sit behind the same JWT +
 * {@code ROLE_DATAHUB_ACCESS} gate as {@code /analysis} (no MCP-specific bypass), and a
 * {@code tools/list} must advertise {@code analysis_related_series}. Mirrors
 * {@code AnalysisSecurityTest}'s harness.
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test/realms/datahub")
class McpEndpointTest {

    /** The Accept value the stateless transport requires byte-exact (compared with MediaType.equals). */
    private static final String ACCEPT = "application/json, text/event-stream";

    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private JwtDecoder jwtDecoder; // prevents a real issuer lookup; satisfies the resource-server wiring

    @MockitoBean
    private AnalysisService analysisService; // advertising tools must not touch the orchestration

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void mcpIsBehindTheSameGateAsAnalysis() throws Exception {
        mvc.perform(post("/mcp").header("Accept", ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON).content(TOOLS_LIST))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("Accept", ACCEPT).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(TOOLS_LIST))
                .andExpect(status().isForbidden());
    }

    @Test
    void advertisesTheAnalysisTool() throws Exception {
        mvc.perform(post("/mcp").header("Accept", ACCEPT)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATAHUB_ACCESS")))
                        .contentType(MediaType.APPLICATION_JSON).content(TOOLS_LIST))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("analysis_related_series")));
    }
}
