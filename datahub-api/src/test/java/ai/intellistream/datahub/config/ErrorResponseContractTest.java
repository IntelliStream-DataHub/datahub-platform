// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.ApiDatahubApplication;
import ai.intellistream.datahub.api.init.pulsar.SubscriptionTopicProvisioner;
import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Every refusal the servlet container or the security chain produces arrives as a problem document.
 * A real port rather than MockMvc, because MockMvc performs no error dispatch and these bodies are
 * rendered there.
 */
@SpringBootTest(classes = ApiDatahubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ctxtest")
@Import(ErrorResponseContractTest.Throwing.class)
class ErrorResponseContractTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WITH_ROLE = "tok-with-access-role";
    private static final String WITHOUT_ROLE = "tok-without-access-role";
    private static final String EXPIRED = "tok-expired";
    private static final String UNDECODABLE = "tok-undecodable";
    private static final String SECRET = "jdbc:postgresql://db.internal:5432/tenant_x";

    @TestConfiguration
    @RestController
    static class Throwing {
        @GetMapping("/__contract/boom")
        String boom() {
            throw new IllegalStateException("Connection refused: " + SECRET);
        }
    }

    @Value("${local.server.port}") private int port;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private PulsarClient pulsarClient;
    @MockitoBean private PulsarAdmin pulsarAdmin;
    @MockitoBean private ClickHouseClientPool clickHouseClientPool;
    @MockitoBean private SubscriptionTopicProvisioner subscriptionTopicProvisioner;
    @MockitoBean private InstanceLock instanceLock;
    @MockitoBean(name = "eventMessageProducer") private Producer<?> eventMessageProducer;
    @MockitoBean(name = "subscriptionNotifyProducer") private Producer<?> subscriptionNotifyProducer;
    @MockitoBean(name = "allDatapointProducer") private Producer<?> allDatapointProducer;
    @MockitoBean(name = "httpMessageProducer") private Producer<?> httpMessageProducer;

    @BeforeEach
    void stubTokens() {
        when(jwtDecoder.decode(WITH_ROLE)).thenReturn(jwt(List.of("DATAHUB_ACCESS")));
        when(jwtDecoder.decode(WITHOUT_ROLE)).thenReturn(jwt(List.of("SOME_OTHER_ROLE")));
        when(jwtDecoder.decode(EXPIRED)).thenThrow(new JwtValidationException("expired",
                List.of(new OAuth2Error("invalid_token", "Jwt expired at 2026-01-01T00:00:00Z", null))));
        when(jwtDecoder.decode(UNDECODABLE)).thenThrow(new BadJwtException("Nimbus: Invalid serialized JWS object"));
    }

    @Test
    @DisplayName("An uncaught exception is a 500 problem, not a 200 with an empty body, and its text stays in the log")
    void uncaughtExceptionIsAnInternalProblem() throws Exception {
        HttpResponse<String> response = send("GET", "/__contract/boom", WITH_ROLE, null, null);

        assertProblem(response, 500, "internal");
        assertThat(response.body()).doesNotContain(SECRET).doesNotContain("IllegalStateException");
        assertThat(json(response).path("instance").asString()).isEqualTo("/__contract/boom");
    }

    @Test
    @DisplayName("No token: 401 problem with WWW-Authenticate")
    void anonymousIsAnUnauthorizedProblem() throws Exception {
        HttpResponse<String> response = send("GET", "/resources", null, null, null);

        assertProblem(response, 401, "unauthorized");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).startsWith("Bearer"));
        assertThat(json(response).path("detail").asString()).contains("Authorization header");
    }

    @Test
    @DisplayName("A failed token check says which check failed")
    void validatorDescriptionIsForwarded() throws Exception {
        HttpResponse<String> response = send("GET", "/resources", EXPIRED, null, null);

        assertProblem(response, 401, "unauthorized");
        assertThat(json(response).path("detail").asString()).isEqualTo("Jwt expired at 2026-01-01T00:00:00Z");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).contains("invalid_token"));
    }

    @Test
    @DisplayName("A decoder's own exception text is not forwarded")
    void decoderTextIsNotForwarded() throws Exception {
        HttpResponse<String> response = send("GET", "/resources", UNDECODABLE, null, null);

        assertProblem(response, 401, "unauthorized");
        assertThat(response.body()).doesNotContain("Nimbus");
    }

    @Test
    @DisplayName("A token without DATAHUB_ACCESS: 403 problem that names the role, header kept")
    void missingRoleIsAForbiddenProblem() throws Exception {
        HttpResponse<String> response = send("GET", "/resources", WITHOUT_ROLE, null, null);

        assertProblem(response, 403, "forbidden");
        assertThat(json(response).path("detail").asString()).contains("DATAHUB_ACCESS");
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(h -> assertThat(h).contains("insufficient_scope"));
    }

    @Test
    @DisplayName("Spring MVC's own refusals are problems too")
    void frameworkRefusalsAreProblems() throws Exception {
        assertProblem(send("GET", "/__no-such-path", WITH_ROLE, null, null), 404, "not-found");
        assertProblem(send("PATCH", "/labels", WITH_ROLE, "application/json", "{}"), 405, "method-not-allowed");
        assertProblem(send("POST", "/edges/create", WITH_ROLE, "text/plain", "x"), 415, "unsupported-media-type");

        HttpResponse<String> missingParam = send("GET", "/files/search", WITH_ROLE, null, null);
        assertProblem(missingParam, 400, "bad-request");
        assertThat(json(missingParam).path("detail").asString()).contains("q");
    }

    private void assertProblem(HttpResponse<String> response, int status, String typeSlug) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = json(response);
        assertThat(body.path("type").asString()).isEqualTo("https://intellistream.ai/errors/" + typeSlug);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("title").asString()).isNotBlank();
        assertThat(body.has("properties")).isFalse();
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String token, String contentType, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Jwt jwt(List<String> realmRoles) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("organization", Map.of("org", Map.of("id", "tenant-error-contract")))
                .claim("realm_access", Map.of("roles", realmRoles))
                .subject("error-response-contract-test")
                .build();
    }
}
