// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The flow service's own Keycloak client ({@code intellistream-flow}, confidential): the
 * authorization-code round-trip that makes a person a flow's identity, refreshing the offline token
 * that round-trip yields, client-credentials tokens for registered clients, and revoking an offline
 * token that a new deployment replaced.
 *
 * <p>The endpoints come from the issuer's discovery document, read once and kept. Nothing here
 * logs a token.
 *
 * <p>Failures come in two kinds, because they are handled differently: {@link Rejected} means
 * Keycloak answered and said no (a revoked offline token, a wrong client secret) — retrying will not
 * help, the identity has to be renewed; {@link Unavailable} means Keycloak could not be asked, which
 * a later attempt may get past.
 */
@Slf4j
@Component
public class KeycloakTokens {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final JsonMapper json;
    private final HttpClient http;
    private volatile Endpoints endpoints;

    /** The endpoints this service uses, from the issuer's discovery document. */
    record Endpoints(String authorization, String token, String revocation) {
    }

    /** What a token endpoint answered: the access token, the refresh token if any, and how long the access token lasts. */
    public record Tokens(String accessToken, String refreshToken, Duration expiresIn) {
    }

    /** Keycloak refused: the grant, the client or its credentials are no longer good. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    /** Keycloak could not be asked, or answered with an error of its own. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Autowired
    public KeycloakTokens(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${datahub.flow.keycloak.client-id:intellistream-flow}") String clientId,
            @Value("${datahub.flow.keycloak.client-secret:}") String clientSecret,
            JsonMapper json) {
        this(issuer, clientId, clientSecret, json,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    KeycloakTokens(String issuer, String clientId, String clientSecret, JsonMapper json, HttpClient http) {
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.json = json;
        this.http = http;
    }

    /** This service's client secret, which also keys the signature on a deploy's {@code state}. */
    String clientSecret() {
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("datahub.flow.keycloak.client-secret is not set: the flow "
                    + "service cannot deploy flows or act as their deployers without its Keycloak client.");
        }
        return clientSecret;
    }

    /** Where to send a person to authorise this service to act for them. */
    public URI authorizationUrl(String redirectUri, String scope, String state) {
        return URI.create(endpoints().authorization() + "?" + form(Map.of(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", redirectUri,
                "scope", scope,
                "state", state)));
    }

    /** Redeems the code an authorization round-trip ended with. */
    public Tokens exchangeCode(String code, String redirectUri) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        return token(clientId, clientSecret(), body);
    }

    /** A fresh access token for an offline token. */
    public Tokens refresh(String refreshToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        return token(clientId, clientSecret(), body);
    }

    /** A token for a registered client, with its own credentials. */
    public Tokens clientCredentials(String id, String secret, String scope) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("scope", scope);
        return token(id, secret, body);
    }

    /**
     * Revokes an offline token, so the session it holds open ends with it. Best effort: a token
     * that is already gone, or a Keycloak that cannot be reached, is logged and left.
     */
    public void revoke(String refreshToken) {
        String endpoint = endpoints().revocation();
        if (endpoint == null) {
            log.warn("Keycloak advertises no revocation endpoint; a replaced offline token stays "
                    + "valid until it expires or its session is ended");
            return;
        }
        try {
            HttpResponse<String> response = http.send(post(endpoint, clientId, clientSecret(), Map.of(
                    "token", refreshToken, "token_type_hint", "refresh_token")),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Keycloak did not revoke a replaced offline token: HTTP {}", response.statusCode());
            }
        } catch (IOException e) {
            log.warn("Could not revoke a replaced offline token: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Tokens token(String id, String secret, Map<String, String> body) {
        HttpResponse<String> response;
        try {
            response = http.send(post(endpoints().token(), id, secret, body),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Unavailable("Keycloak could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while asking Keycloak for a token", e);
        }
        int status = response.statusCode();
        if (status == 200) {
            JsonNode answer = json.readTree(response.body());
            String refresh = answer.path("refresh_token").asString(null);
            return new Tokens(answer.path("access_token").asString(), refresh,
                    Duration.ofSeconds(answer.path("expires_in").asLong(60)));
        }
        // RFC 6749 §5.2: a 400 or 401 with an error code is Keycloak deciding against the grant or
        // the client. Anything else is Keycloak not working.
        if (status == 400 || status == 401) {
            String error = errorOf(response.body());
            throw new Rejected("Keycloak refused the " + body.get("grant_type") + " grant: " + error);
        }
        throw new Unavailable("Keycloak answered HTTP " + status + " to a token request", null);
    }

    private String errorOf(String body) {
        try {
            JsonNode answer = json.readTree(body);
            String error = answer.path("error").asString("unknown");
            String description = answer.path("error_description").asString(null);
            return description == null ? error : error + " (" + description + ")";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static HttpRequest post(String endpoint, String id, String secret, Map<String, String> body) {
        String basic = Base64.getEncoder().encodeToString(
                (encode(id) + ":" + encode(secret)).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                .build();
    }

    private Endpoints endpoints() {
        Endpoints known = endpoints;
        if (known != null) {
            return known;
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(issuer + "/.well-known/openid-configuration"))
                    .timeout(TIMEOUT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new Unavailable("Keycloak's discovery document answered HTTP "
                        + response.statusCode(), null);
            }
            JsonNode doc = json.readTree(response.body());
            known = new Endpoints(doc.path("authorization_endpoint").asString(),
                    doc.path("token_endpoint").asString(),
                    doc.path("revocation_endpoint").asString(null));
            endpoints = known;
            return known;
        } catch (IOException e) {
            throw new Unavailable("Keycloak's discovery document could not be read: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while reading Keycloak's discovery document", e);
        }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    /** RFC 6749 §2.3.1: client credentials in Basic auth are form-encoded first. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
