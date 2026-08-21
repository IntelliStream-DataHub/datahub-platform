// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.auth;

import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Supplies a bearer token for API calls. A static token (from config) is returned as-is.
 * Otherwise a token is fetched with either the OAuth2 client-credentials grant or, when an
 * assertion source is configured, the RFC 7523 {@code jwt-bearer} grant. Either way the result is
 * cached until shortly before it expires; refresh is single-flight — concurrent callers trigger at
 * most one exchange.
 */
public final class TokenProvider {

    private static final long EXPIRY_SKEW_SECONDS = 30;
    private static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private final DatahubConfig config;
    private final HttpClient http;
    private final JsonMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant expiresAt;

    public TokenProvider(DatahubConfig config, HttpClient http, JsonMapper mapper) {
        this.config = config;
        this.http = http;
        this.mapper = mapper;
    }

    public String getToken() {
        if (config.hasStaticToken()) {
            return config.token();
        }
        String token = cachedToken;
        if (token != null && expiresAt != null && Instant.now().isBefore(expiresAt)) {
            return token;
        }
        return refresh();
    }

    private String refresh() {
        lock.lock();
        try {
            // Another thread may have refreshed while we waited for the lock.
            if (cachedToken != null && expiresAt != null && Instant.now().isBefore(expiresAt)) {
                return cachedToken;
            }
            TokenResponse tr = config.hasJwtBearer() ? requestJwtBearer() : requestClientCredentials();
            cachedToken = tr.accessToken();
            long ttl = Math.max(0, tr.expiresIn() - EXPIRY_SKEW_SECONDS);
            expiresAt = Instant.now().plusSeconds(ttl);
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    private TokenResponse requestClientCredentials() {
        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        appendParam(body, "scope", config.scope());
        appendParam(body, "audience", config.audience());
        return post(config.tokenUri(), config.clientId(), config.clientSecret(), body.toString(),
                "client-credentials token request failed");
    }

    /**
     * RFC 7523: present an externally-issued JWT as an {@code assertion} and get back a token from
     * this provider. Used to bridge an identity from another issuer — an Entra ID service
     * principal, say — into a Keycloak token the API will accept.
     *
     * <p>The assertion is deliberately not cached: providers commonly reject a replayed assertion
     * (Keycloak defaults to one-time use), so each exchange starts from a fresh one.
     */
    private TokenResponse requestJwtBearer() {
        StringBuilder body = new StringBuilder("grant_type=")
                .append(URLEncoder.encode(JWT_BEARER_GRANT, StandardCharsets.UTF_8));
        appendParam(body, "assertion", assertion());
        appendParam(body, "scope", config.scope());
        appendParam(body, "audience", config.audience());
        return post(config.tokenUri(), config.clientId(), config.clientSecret(), body.toString(),
                "jwt-bearer token request failed");
    }

    /** The configured static assertion, or one fetched from the assertion provider. */
    private String assertion() {
        if (config.assertion() != null && !config.assertion().isBlank()) {
            return config.assertion();
        }
        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        appendParam(body, "scope", config.assertionScope());
        appendParam(body, "audience", config.assertionAudience());
        return post(config.assertionTokenUri(), config.assertionClientId(), config.assertionClientSecret(),
                body.toString(), "assertion request failed").accessToken();
    }

    private TokenResponse post(String tokenUri, String clientId, String clientSecret, String body, String failure) {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new DatahubApiException(0, "token request failed: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatahubApiException(0, "token request interrupted", null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DatahubApiException(response.statusCode(), failure, response.body());
        }
        return mapper.readValue(response.body(), TokenResponse.class);
    }

    private static void appendParam(StringBuilder body, String name, String value) {
        if (value != null && !value.isBlank()) {
            body.append('&').append(name).append('=')
                    .append(URLEncoder.encode(value.strip(), StandardCharsets.UTF_8));
        }
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
