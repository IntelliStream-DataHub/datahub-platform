// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.tenant.TenantFlowSection.McpClientCredentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client-credentials grant against a loopback token endpoint, on a clock the test moves. */
class ClientCredentialsTokensTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private TokenEndpoint endpoint;
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void start() throws IOException {
        endpoint = new TokenEndpoint();
    }

    @AfterEach
    void stop() {
        endpoint.http.stop(0);
    }

    @Test
    void takesATokenAsTheClientAndAsksForScopeAndResourceOnlyWhenGiven() {
        StreamableMcpClient.Credential credential = tokens(true).credential("crm",
                new McpClientCredentials(endpoint.url(), "flow agent", "s&cret", "tickets:read tickets:write",
                        "https://crm.example/mcp"));

        assertThat(credential.headerName()).isEqualTo("Authorization");
        assertThat(credential.headerValue()).isEqualTo("Bearer t-1");
        TokenEndpoint.Request request = endpoint.requests.getFirst();
        // RFC 6749 §2.3.1: form-encoded, then joined and base64-encoded.
        assertThat(new String(Base64.getDecoder().decode(request.authorization().substring("Basic ".length())),
                StandardCharsets.UTF_8)).isEqualTo("flow+agent:s%26cret");
        assertThat(request.body()).isEqualTo("grant_type=client_credentials&scope=tickets%3Aread+tickets%3Awrite"
                + "&resource=https%3A%2F%2Fcrm.example%2Fmcp");

        tokens(true).credential("crm", new McpClientCredentials(endpoint.url(), "c", "s", null, null)).headerValue();
        assertThat(endpoint.requests.getLast().body()).isEqualTo("grant_type=client_credentials");
    }

    @Test
    void aTokenIsSharedUntilShortlyBeforeItExpires() {
        ClientCredentialsTokens tokens = tokens(true);
        McpClientCredentials grant = new McpClientCredentials(endpoint.url(), "c", "s", null, null);
        endpoint.expiresIn = 300;

        assertThat(tokens.credential("crm", grant).headerValue()).isEqualTo("Bearer t-1");
        clock = Clock.fixed(NOW.plusSeconds(239), ZoneOffset.UTC);
        assertThat(tokens.credential("crm", grant).headerValue()).isEqualTo("Bearer t-1");
        // 300 s, renewed 30 s early: a tenth of its lifetime, being under a minute.
        clock = Clock.fixed(NOW.plusSeconds(270), ZoneOffset.UTC);
        assertThat(tokens.credential("crm", grant).headerValue()).isEqualTo("Bearer t-2");

        // A rotated secret is another grant, and takes a token of its own.
        assertThat(tokens.credential("crm", new McpClientCredentials(endpoint.url(), "c", "s2", null, null))
                .headerValue()).isEqualTo("Bearer t-3");
        assertThat(endpoint.requests).hasSize(3);
    }

    @Test
    void renewingForgetsTheRefusedTokenButNotOneAlreadyReplacingIt() {
        ClientCredentialsTokens tokens = tokens(true);
        McpClientCredentials grant = new McpClientCredentials(endpoint.url(), "c", "s", null, null);
        StreamableMcpClient.Credential credential = tokens.credential("crm", grant);
        String refused = credential.headerValue();

        assertThat(credential.renew(refused)).isTrue();
        assertThat(credential.headerValue()).isEqualTo("Bearer t-2");
        // Another session renewing the same old token later does not throw away t-2.
        assertThat(credential.renew(refused)).isTrue();
        assertThat(credential.headerValue()).isEqualTo("Bearer t-2");
        assertThat(endpoint.requests).hasSize(2);
    }

    @Test
    void aRefusedClientIsUnauthorizedAndSaysWhatTheEndpointSaid() {
        endpoint.status = 401;
        endpoint.reply = "{\"error\":\"invalid_client\",\"error_description\":\"no such client\"}";

        assertThatThrownBy(() -> tokens(true).credential("crm",
                new McpClientCredentials(endpoint.url(), "c", "s", null, null)).headerValue())
                .isInstanceOf(McpException.class)
                .hasMessage("crm: the token endpoint refused the client (HTTP 401, invalid_client)")
                .satisfies(e -> assertThat(((McpException) e).isUnauthorized()).isTrue());
    }

    @Test
    void onlyABearerTokenIsUsed() {
        endpoint.reply = "{\"access_token\":\"m\",\"token_type\":\"mac\"}";

        assertThatThrownBy(() -> tokens(true).credential("crm",
                new McpClientCredentials(endpoint.url(), "c", "s", null, null)).headerValue())
                .isInstanceOf(McpException.class)
                .hasMessageContaining("only Bearer tokens");
    }

    @Test
    void aPrivateTokenEndpointIsRefusedBeforeAnyRequestUnlessTheDeploymentAllowsIt() {
        assertThatThrownBy(() -> tokens(false).credential("crm",
                new McpClientCredentials(endpoint.url(), "c", "s", null, null)).headerValue())
                .isInstanceOf(McpException.class)
                .hasMessageContaining("private or local address");
        assertThat(endpoint.requests).isEmpty();
    }

    private ClientCredentialsTokens tokens(boolean allowPrivate) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        // The clock is read through, so a test can move it after the tokens are made.
        Clock moving = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clock.instant();
            }
        };
        return new ClientCredentialsTokens(http, JSON, Duration.ofSeconds(5), allowPrivate, moving);
    }

    /** A token endpoint that issues t-1, t-2, … and records what it was asked. */
    private static final class TokenEndpoint {
        record Request(String authorization, String body) {
        }

        final HttpServer http;
        final List<Request> requests = new ArrayList<>();
        int status = 200;
        long expiresIn = 3600;
        String reply;

        TokenEndpoint() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/token", this::handle);
            http.start();
        }

        String url() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/token";
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(new Request(exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String body = reply != null ? reply : "{\"access_token\":\"t-" + requests.size()
                    + "\",\"token_type\":\"bearer\",\"expires_in\":" + expiresIn + "}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        }
    }
}
