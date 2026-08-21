// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the caller's organization group membership from the identity provider's <strong>UserInfo</strong>
 * endpoint, using the caller's own bearer token.
 *
 * <h2>Why UserInfo rather than the access token</h2>
 * The dataset grants could be carried in the JWT itself, but they are deliberately not:
 * <ul>
 *   <li><strong>Token size</strong> — group paths are longer than role names and the
 *       {@code Authorization} header travels on every request, including the JWT
 *       {@code datahub-analysis} forwards through the SDK.</li>
 *   <li><strong>Revocation</strong> — a group removed in Keycloak has no effect until the token
 *       expires. Read out of band, revocation is bounded by the cache TTL in
 *       {@link OrgGroupResolver} instead.</li>
 *   <li><strong>Entra ID overage</strong> — where membership is federated from Entra, its group
 *       claim cuts out past roughly 150 to 200 groups and is replaced by a Graph pointer.</li>
 * </ul>
 * Using the caller's own token (rather than an admin service account) means this needs no
 * privileged credential and no extra Vault secret, and the answer is naturally scoped to the caller.
 *
 * <h2>Required Keycloak configuration</h2>
 * The {@code oidc-organization-group-membership-mapper} must be on the {@code organization} client
 * scope with {@code userinfo.token.claim=true}, and clients must request
 * {@code scope=organization:<alias>}. See {@code datahub-api/KEYCLOAK_ORG_GROUPS.md} — including
 * the three configuration traps that silently produce an empty or absent claim.
 *
 * <h2>Response shape</h2>
 * <pre>{@code
 * { "sub": "...", "organization": { "acme": { "id": "<tenant-id>", "groups": ["/datasets/x/read"] } } }
 * }</pre>
 * Keyed by organization <em>alias</em>, but the returned map is keyed by the organization
 * <strong>id</strong>, because that is what {@code TenantContext} holds.
 */
@Component
@Slf4j
public class KeycloakUserInfoClient {

    private final HttpClient http;
    private final JsonMapper jsonMapper;
    private final String issuerUri;
    private final Duration requestTimeout;

    /**
     * Discovered lazily and memoised. Not resolved in the constructor: bean creation must not
     * depend on the identity provider being reachable at that instant, and a failed discovery has
     * to stay retryable rather than poisoning the bean.
     */
    private volatile String userInfoEndpoint;

    public KeycloakUserInfoClient(
            JsonMapper jsonMapper,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${datahub.acl.groups.http-timeout:3s}") Duration requestTimeout) {
        this.jsonMapper = jsonMapper;
        this.issuerUri = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                // Timeouts matter on this path: it runs inside request handling, so a hung
                // identity provider must fail fast rather than pin threads until the socket dies.
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * The caller's organization group paths, keyed by organization id. Group paths are relative to
     * their organization, e.g. {@code /datasets/data_set_sap/read}.
     *
     * <p>An organization the caller belongs to but holds no groups in maps to an empty list. An
     * organization absent from the claim entirely is absent from the map — the two are different,
     * and only the caller can tell whether a missing organization means "no groups" or "the client
     * never requested {@code organization:<alias>} scope".
     *
     * @throws UserInfoUnavailableException if UserInfo cannot be reached or refuses the token
     */
    public Map<String, List<String>> fetchGroupsByOrganizationId(String bearerToken) {
        String endpoint = resolveUserInfoEndpoint();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json")
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserInfoUnavailableException("Interrupted while calling UserInfo", e);
        } catch (Exception e) {
            throw new UserInfoUnavailableException("UserInfo request failed: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            // 401/403 here means the token was rejected (expired mid-request, or the session was
            // ended server-side). Treated the same as unreachable: the caller fails closed rather
            // than falling back to "no groups", which would look like a legitimate empty grant.
            //
            // Logged, not thrown: Keycloak names the failed check in
            // {"error":"invalid_token","error_description":"..."}, and without it every cause looks
            // identical from the outside. It stays here because this exception's message is not
            // private - Spring AI's MCP server reports a failed tool as its getMessage(), so
            // anything added there travels to the chat model and on into a user-visible answer.
            log.warn("UserInfo rejected the caller's token with HTTP {}: {}",
                    response.statusCode(), response.body());
            throw new UserInfoUnavailableException(
                    "UserInfo returned HTTP " + response.statusCode());
        }

        return parseOrganizationGroups(response.body());
    }

    Map<String, List<String>> parseOrganizationGroups(String body) {
        Map<String, List<String>> byOrgId = new HashMap<>();
        JsonNode organization;
        try {
            organization = jsonMapper.readTree(body).path("organization");
        } catch (Exception e) {
            throw new UserInfoUnavailableException("Could not parse UserInfo response", e);
        }
        if (!organization.isObject()) {
            return byOrgId;
        }
        for (Map.Entry<String, JsonNode> entry : organization.properties()) {
            JsonNode org = entry.getValue();
            String orgId = org.path("id").asString(null);
            if (orgId == null) {
                // Without addOrganizationId=true on the membership mapper the claim carries no id,
                // so there is nothing to match TenantContext against. See KEYCLOAK_ORG_GROUPS.md.
                log.warn("UserInfo organization '{}' has no id; check addOrganizationId on the "
                        + "organization membership mapper", entry.getKey());
                continue;
            }
            List<String> groups = new ArrayList<>();
            for (JsonNode g : org.path("groups")) {
                String path = g.asString(null);
                if (path != null && !path.isBlank()) {
                    groups.add(path);
                }
            }
            byOrgId.put(orgId, List.copyOf(groups));
        }
        return byOrgId;
    }

    private String resolveUserInfoEndpoint() {
        String cached = userInfoEndpoint;
        if (cached != null) {
            return cached;
        }
        // Discovered rather than assembled by hand: the path is provider-specific, and the api
        // already cannot start without this document (SecurityConfig's JwtDecoders.fromIssuerLocation
        // fetches it too). Benign to race — both threads compute the same value.
        String discoveryUrl = issuerUri + "/.well-known/openid-configuration";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(discoveryUrl))
                    .header("Accept", "application/json")
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new UserInfoUnavailableException(
                        "OIDC discovery returned HTTP " + response.statusCode() + " for " + discoveryUrl);
            }
            String endpoint = jsonMapper.readTree(response.body()).path("userinfo_endpoint").asString(null);
            if (endpoint == null || endpoint.isBlank()) {
                throw new UserInfoUnavailableException("No userinfo_endpoint in " + discoveryUrl);
            }
            userInfoEndpoint = endpoint;
            log.info("Resolved UserInfo endpoint: {}", endpoint);
            return endpoint;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserInfoUnavailableException("Interrupted during OIDC discovery", e);
        } catch (UserInfoUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new UserInfoUnavailableException("OIDC discovery failed: " + e.getMessage(), e);
        }
    }

    /** Test seam: pre-set the endpoint so a test never performs discovery. */
    void setUserInfoEndpoint(String endpoint) {
        this.userInfoEndpoint = endpoint;
    }
}
