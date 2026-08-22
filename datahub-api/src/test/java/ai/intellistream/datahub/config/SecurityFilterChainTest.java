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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Authorization tests for the real {@link SecurityConfig} filter chain, driven over real HTTP.
 *
 * <p>These cover the one rule nothing else in this module tests: <em>every non-public endpoint
 * requires {@code ROLE_DATAHUB_ACCESS}</em>. That rule lives in a single {@code anyRequest()} line
 * of {@code SecurityConfig}, and until now no test read it. The controller tests build a
 * stand-alone {@code MockMvc} that deliberately bypasses the chain (see
 * {@code TimeseriesControllerTest}), {@code OrganizationValidatorTest} exercises the token
 * validator in isolation, and {@code ApiDatahubApplicationTests} boots the context with the
 * decoder mocked out. So deleting the role check — or adding a stray {@code permitAll()} — would
 * have left every one of those tests green while opening the API to any authenticated caller.
 *
 * <p>The chain is exercised end to end: a real embedded servlet container, real filters, real
 * {@code TestRestTemplate} requests. Only {@link JwtDecoder} is replaced, because the production
 * bean performs live OIDC discovery against the issuer; stubbing it lets a test present an
 * arbitrary token without a Keycloak. Everything downstream of the decoder — the
 * {@code JwtAuthenticationConverter} that maps {@code realm_access.roles} to {@code ROLE_} 
 * authorities, and the authorization rules themselves — is the production code.
 *
 * <p>The remaining {@code @MockitoBean}s are the start-up network clients, mocked for the same
 * reasons as in {@code ApiDatahubApplicationTests}; keeping the set identical (same mocks, same
 * profile, same web environment) lets both classes share one cached application context.
 *
 * <p>Note that the secured-path assertions hold whether or not a path maps to a handler: the chain
 * runs before handler resolution, so an unknown path under the default rule is rejected exactly
 * like a known one. That is the deny-by-default property being pinned, and it is why
 * {@link #roleOpensTheGate} probes an unmapped path — it isolates the authorization outcome from
 * whatever a real controller would do once admitted.
 */
// The application class sits in a sibling package (…datahub.api), so it is named explicitly
// rather than found by the upward package scan from this test's package.
@SpringBootTest(
        classes = ApiDatahubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ctxtest")
class SecurityFilterChainTest {

    /** Redirects are not followed: a 302 would mask the status the chain actually produced. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** A token string with no stub behind it decodes to null/invalid — used for the 401 cases. */
    private static final String TOKEN_WITH_ROLE = "tok-with-access-role";
    private static final String TOKEN_WITHOUT_ROLE = "tok-without-access-role";

    /**
     * One representative path per controller, plus the MCP base path. The MCP tools are served as
     * ordinary MVC endpoints on this same chain, so they must be gated by the same role — a claim
     * AGENTS.md makes and this pins.
     */
    private static Stream<String> securedPaths() {
        return Stream.of(
                "/datasets", "/edges", "/events", "/files", "/functions", "/governance", "/labels",
                "/policies", "/resources", "/stats", "/subscriptions", "/tenant", "/timeseries",
                "/units", "/mcp");
    }

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;


    @MockitoBean
    private PulsarClient pulsarClient;

    @MockitoBean
    private PulsarAdmin pulsarAdmin;

    @MockitoBean
    private ClickHouseClientPool clickHouseClientPool;

    @MockitoBean
    private SubscriptionTopicProvisioner subscriptionTopicProvisioner;

    @MockitoBean
    private InstanceLock instanceLock;

    @MockitoBean(name = "resourceMessageProducer")
    private Producer<?> resourceMessageProducer;

    @MockitoBean(name = "eventMessageProducer")
    private Producer<?> eventMessageProducer;

    @MockitoBean(name = "subscriptionNotifyProducer")
    private Producer<?> subscriptionNotifyProducer;

    @MockitoBean(name = "allDatapointProducer")
    private Producer<?> allDatapointProducer;

    @MockitoBean(name = "httpMessageProducer")
    private Producer<?> httpMessageProducer;

    @BeforeEach
    void stubTokens() {
        when(jwtDecoder.decode(TOKEN_WITH_ROLE))
                .thenReturn(jwtWithRealmRoles(List.of("DATAHUB_ACCESS")));
        // Authenticates fine, but carries no DATAHUB_ACCESS role. This is the case the rule exists
        // for: a legitimately issued token that must still not reach any data.
        when(jwtDecoder.decode(TOKEN_WITHOUT_ROLE))
                .thenReturn(jwtWithRealmRoles(List.of("SOME_OTHER_ROLE")));
    }

    @ParameterizedTest(name = "{0} rejects an anonymous caller with 401")
    @MethodSource("securedPaths")
    @DisplayName("Every secured endpoint rejects a request with no token")
    void anonymousIsUnauthorized(String path) {
        assertThat(get(path, null))
                .as("%s must require authentication", path)
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @ParameterizedTest(name = "{0} rejects a token without DATAHUB_ACCESS with 403")
    @MethodSource("securedPaths")
    @DisplayName("A valid token without DATAHUB_ACCESS is forbidden everywhere")
    void validTokenWithoutRoleIsForbidden(String path) {
        assertThat(get(path, TOKEN_WITHOUT_ROLE))
                .as("%s must require the DATAHUB_ACCESS role, not merely a valid token", path)
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("DATAHUB_ACCESS opens the gate, so the 403s above are caused by the missing role")
    void roleOpensTheGate() {
        // An unmapped path: it passes authorization and then 404s at handler resolution. Using a
        // real controller path here would drag in the datasource and services, which say nothing
        // about authorization; what matters is only that the chain no longer rejects the caller.
        assertThat(get("/__authz-probe", TOKEN_WITH_ROLE))
                .as("a caller holding DATAHUB_ACCESS must get past the authorization filter")
                .isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @ParameterizedTest(name = "{0} stays reachable without a token")
    @MethodSource("publicPaths")
    @DisplayName("The deliberately public paths are not locked behind the role")
    void publicPathsRemainPublic(String path) {
        // Guards the inverse regression: tightening the chain must not silently break the docs
        // UI or the live-tail handshake. Any non-401 outcome (200 here, 400 for the handshake)
        // means authorization let the request through, which is all that is asserted.
        //
        // Note this only holds for paths that resolve to a handler or resource. Spring Security
        // filters the ERROR dispatch too, so an anonymous request to a permitted-but-unmapped
        // path 404s, forwards to /error, and comes back 401 under the default rule.
        assertThat(get(path, null))
                .as("%s is configured permitAll and must not demand a token", path)
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    /**
     * The {@code permitAll} entries that actually serve something in this module. {@code /session/**}
     * is listed in the chain but no controller here maps it (it is the console that owns session
     * endpoints), so probing it would assert nothing about authorization — see the error-dispatch
     * note in {@link #publicPathsRemainPublic}.
     */
    private static Stream<String> publicPaths() {
        return Stream.of(
                "/swagger-ui/index.html",
                "/api-docs/swagger-config",
                "/static/redoc/redoc.html",
                // The browser live-tail handshake cannot send an Authorization header, so the
                // chain permits it and DatapointListenWebSocketHandler validates the ?token=
                // itself. A plain GET fails the WebSocket upgrade with 400 — which is the point:
                // it got past authorization.
                "/timeseries/datapoints/listen");
    }

    @Test
    @DisplayName("An unparseable bearer token is rejected as 401, not treated as anonymous")
    void malformedTokenIsUnauthorized() {
        // No stub matches this value, so the mocked decoder returns null and the resource server
        // treats the credentials as invalid.
        assertThat(get("/resources", "not-a-real-token"))
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ---- helpers -----------------------------------------------------------------------------

    /**
     * Issues a real GET against the embedded container and returns the status code. The JDK client
     * is used rather than a Spring test client so the request is an ordinary HTTP call with no
     * framework-side request post-processing — what reaches the filter chain is exactly what a
     * caller on the network would send.
     */
    private int get(String path, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Request to " + path + " failed", e);
        }
    }

    /**
     * A decoded token shaped like Keycloak's: the organization claim the tenant resolves from, and
     * realm roles in the {@code realm_access.roles} array the production converter reads.
     */
    private static Jwt jwtWithRealmRoles(List<String> realmRoles) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("organization", Map.of("org", Map.of("id", "tenant-authz-test")))
                .claim("realm_access", Map.of("roles", realmRoles))
                .subject("security-filter-chain-test")
                .build();
    }
}
