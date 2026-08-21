// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.compute;

import ai.intellistream.datahub.models.analysis.AnalysisResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code /analysis} trust boundary: an OAuth2 resource server that requires a valid JWT carrying
 * {@code ROLE_DATAHUB_ACCESS} — the token the browser presents (and this service forwards to the api).
 * The {@link JwtDecoder} is mocked so no real issuer is contacted (and the Vault issuer loader,
 * registered only in {@code main}, never runs here); spring-security-test's {@code jwt()} supplies
 * the authentication.
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test/realms/datahub")
class AnalysisSecurityTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private JwtDecoder jwtDecoder; // prevents a real issuer lookup; satisfies the resource-server wiring

    @MockitoBean
    private AnalysisService analysisService; // testing auth, not the orchestration

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mvc.perform(post("/analysis").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWithoutRole() throws Exception {
        mvc.perform(post("/analysis").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsTokenWithDatahubAccessRole() throws Exception {
        when(analysisService.analyze(any())).thenReturn(new AnalysisResponse());
        mvc.perform(post("/analysis")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATAHUB_ACCESS")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
}
