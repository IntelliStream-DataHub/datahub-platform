// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.helpers.utils.OutboundUrls;
import ai.intellistream.datahub.tenant.TenantFlowSection.McpClientCredentials;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Access tokens for the tool servers a tenant registered with an OAuth 2.0 client-credentials grant
 * (RFC 6749 §4.4), taken at the tenant's token endpoint and kept until shortly before they expire,
 * so that runs and the agent form share one token per grant instead of asking for one per session.
 *
 * <p>The client authenticates with HTTP Basic, which every token endpoint must accept;
 * {@code scope} and {@code resource} are sent only when the tenant gave them, since some endpoints
 * refuse a parameter they do not know. The token endpoint is the tenant's URL, so it gets the same
 * treatment as the server's: checked against private networks right before it is called, no
 * redirects (the {@code HttpClient} is the caller's), and a bounded reply.
 *
 * <p>Keyed by the whole grant, secret included, so a rotated secret or a changed scope takes a new
 * token rather than reusing one the old grant earned. Two runs that find no token at once both ask
 * for one; the second simply replaces the first.
 */
public final class ClientCredentialsTokens {

    /** A token endpoint's answer is a few hundred bytes; anything near this is not one. */
    private static final long MAX_BODY = 64 << 10;
    /** The lifetime assumed when the endpoint states none: short, since a 401 renews it anyway. */
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);
    /** How long before it expires a token is replaced, at most: a call should not set out with a dying token. */
    private static final Duration EARLY = Duration.ofSeconds(60);

    private record Token(String value, Instant renewAt) {
    }

    private final HttpClient http;
    private final JsonMapper json;
    private final Duration timeout;
    private final boolean allowPrivateNetworks;
    private final Clock clock;
    private final Map<McpClientCredentials, Token> tokens = new ConcurrentHashMap<>();

    public ClientCredentialsTokens(HttpClient http, JsonMapper json, Duration timeout, boolean allowPrivateNetworks,
                                   Clock clock) {
        this.http = http;
        this.json = json;
        this.timeout = timeout;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.clock = clock;
    }

    /** What the tool server {@code server}'s calls carry: {@code Authorization: Bearer}, with a token of {@code grant}. */
    public StreamableMcpClient.Credential credential(String server, McpClientCredentials grant) {
        return new StreamableMcpClient.Credential() {
            @Override
            public String headerName() {
                return "Authorization";
            }

            @Override
            public String headerValue() {
                return "Bearer " + token(server, grant);
            }

            /** Forgets the refused token, unless another call has replaced it already, so the next value is fresh. */
            @Override
            public boolean renew(String refused) {
                tokens.computeIfPresent(grant, (g, held) -> refused.equals("Bearer " + held.value()) ? null : held);
                return true;
            }
        };
    }

    private String token(String server, McpClientCredentials grant) {
        Instant now = clock.instant();
        Token held = tokens.get(grant);
        if (held != null && now.isBefore(held.renewAt())) {
            return held.value();
        }
        Token fresh = fetch(server, grant, now);
        // The one place entries are added, so the one place a grant nobody uses any more is let go.
        tokens.values().removeIf(t -> !now.isBefore(t.renewAt()));
        tokens.put(grant, fresh);
        return fresh.value();
    }

    private Token fetch(String server, McpClientCredentials grant, Instant now) {
        URI endpoint;
        try {
            endpoint = URI.create(grant.tokenUrl());
        } catch (IllegalArgumentException e) {
            throw new McpException(server + ": the token URL is not a URL", false);
        }
        String refusal = OutboundUrls.refuse(endpoint, allowPrivateNetworks);
        if (refusal != null) {
            throw new McpException(server + ": the token endpoint may not be called: " + refusal, false);
        }
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (grant.scope() != null) {
            form.append("&scope=").append(encode(grant.scope()));
        }
        if (grant.resource() != null) {
            form.append("&resource=").append(encode(grant.resource()));
        }
        // RFC 6749 §2.3.1: both halves form-encoded before they are joined and base64-encoded.
        String basic = Base64.getEncoder().encodeToString((encode(grant.clientId()) + ":"
                + encode(grant.clientSecret())).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + basic)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build();
        // sendAsync + get, as McpRpc does: the request's own timeout covers the headers only.
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_BODY));
        HttpResponse<String> response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new McpException(server + ": the token endpoint did not answer within " + timeout.toSeconds() + " s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted taking a token for " + server, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String why = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            throw new McpException(server + ": could not reach the token endpoint " + endpoint + ": " + why, cause);
        }
        return token(server, response, now);
    }

    /** The token in a 200, or the failure the endpoint's answer is. */
    private Token token(String server, HttpResponse<String> response, Instant now) {
        int status = response.statusCode();
        JsonNode body;
        try {
            body = json.readTree(response.body() == null ? "" : response.body());
        } catch (JacksonException e) {
            body = MissingNode.getInstance();
        }
        if (status == 400 || status == 401) {
            // RFC 6749 §5.2: invalid_client, unauthorized_client, invalid_scope, …
            String error = body.path("error").asString("");
            throw new McpException(server + ": the token endpoint refused the client (HTTP " + status
                    + (error.isEmpty() ? "" : ", " + error) + ")", true);
        }
        if (status != 200) {
            throw new McpException(server + ": the token endpoint answered HTTP " + status
                    + (status >= 300 && status < 400 ? ", a redirect, which is not followed" : ""), false);
        }
        String value = body.path("access_token").asString("");
        if (value.isEmpty()) {
            throw new McpException(server + ": the token endpoint answered without an access_token", false);
        }
        String type = body.path("token_type").asString("Bearer");
        if (!type.equalsIgnoreCase("Bearer")) {
            throw new McpException(server + ": the token endpoint issued a " + type
                    + " token; only Bearer tokens are supported", false);
        }
        long seconds = body.path("expires_in").asLong(0);
        Duration lifetime = seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_LIFETIME;
        // A tenth of the lifetime early, and never more than a minute: a 60 s token still gets 54 s.
        Duration early = lifetime.dividedBy(10).compareTo(EARLY) < 0 ? lifetime.dividedBy(10) : EARLY;
        return new Token(value, now.plus(lifetime).minus(early));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
