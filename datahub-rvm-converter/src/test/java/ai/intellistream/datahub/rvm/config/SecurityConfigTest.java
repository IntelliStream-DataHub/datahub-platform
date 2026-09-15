// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm.config;

import ai.intellistream.datahub.rvm.RvmConversion;
import ai.intellistream.datahub.rvm.RvmConverter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.services.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /models trust boundary: a valid JWT with ROLE_DATAHUB_ACCESS, CORS for the console, and CSRF
 * left on without getting in the way of the one GET the console makes.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test/realms/datahub",
        "cors.allowed-origins=http://console.test"})
class SecurityConfigTest {

    private static final String MODEL = "/models/gltf?rvm=plant_rvm";
    private static final String BEARER = "Bearer console-token";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RvmApiClientFactory clients;

    @MockitoBean
    private RvmConverter converter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        FileService files = mock(FileService.class);
        when(files.download("plant_rvm")).thenReturn("rvm".getBytes(StandardCharsets.UTF_8));
        DatahubClient client = mock(DatahubClient.class);
        when(client.files()).thenReturn(files);
        when(clients.forCurrentUser()).thenReturn(client);
        when(converter.convert(any(), any()))
                .thenReturn(new RvmConversion("glTF model".getBytes(StandardCharsets.UTF_8), "ok"));
        when(jwtDecoder.decode("console-token")).thenReturn(Jwt.withTokenValue("console-token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("DATAHUB_ACCESS")))
                .build());
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mvc.perform(get(MODEL)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWithoutRole() throws Exception {
        mvc.perform(get(MODEL).with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void convertsForTheConsolesBearerTokenWithoutACsrfTokenOrASession() throws Exception {
        MvcResult result = mvc.perform(get(MODEL).header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andReturn();
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void refusesAStateChangingRequestWithoutACsrfToken() throws Exception {
        // 403 from the CSRF filter; with CSRF disabled the same request reaches authentication, a 401.
        mvc.perform(post(MODEL)).andExpect(status().isForbidden());
    }

    @Test
    void letsTheConsoleOriginPreflightTheGet() throws Exception {
        mvc.perform(options(MODEL)
                        .header("Origin", "http://console.test")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://console.test"));
    }

    @Test
    void refusesAPreflightForAnyOtherMethod() throws Exception {
        mvc.perform(options(MODEL)
                        .header("Origin", "http://console.test")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }
}
