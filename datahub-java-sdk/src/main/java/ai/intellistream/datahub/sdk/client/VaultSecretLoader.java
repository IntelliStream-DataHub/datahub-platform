// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Reads SDK configuration from a HashiCorp Vault KV v2 secret using only the JDK HttpClient and
 * Jackson — no Vault client dependency. The secret's fields use the same keys as the environment.
 * Supports token auth (use a token directly) and AppRole auth (log in to obtain one).
 */
final class VaultSecretLoader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private VaultSecretLoader() {
    }

    /** Exchange an AppRole {@code roleId}/{@code secretId} for a Vault client token. */
    static String appRoleLogin(String address, String roleId, String secretId, String authMount) {
        requireSet(address, "Vault address");
        requireSet(roleId, "AppRole role_id");
        requireSet(secretId, "AppRole secret_id");
        String url = stripTrailingSlash(address) + "/v1/auth/" + authMount + "/login";
        String requestBody = MAPPER.writeValueAsString(Map.of("role_id", roleId, "secret_id", secretId));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        String body = send(request, "Vault AppRole login " + url);
        LoginResponse parsed = MAPPER.readValue(body, LoginResponse.class);
        if (parsed.auth() == null || parsed.auth().clientToken() == null || parsed.auth().clientToken().isBlank()) {
            throw new DatahubConfigException("Vault AppRole login returned no client_token");
        }
        return parsed.auth().clientToken();
    }

    static Map<String, String> readKvV2(String address, String token, String mount, String path) {
        requireSet(address, "Vault address");
        requireSet(token, "Vault token");
        String url = stripTrailingSlash(address) + "/v1/" + mount + "/data/" + stripLeadingSlash(path);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-Vault-Token", token)
                .header("Accept", "application/json")
                .GET()
                .build();

        String body = send(request, "Vault read " + url);
        VaultResponse parsed = MAPPER.readValue(body, VaultResponse.class);
        if (parsed.data() == null || parsed.data().data() == null || parsed.data().data().isEmpty()) {
            throw new DatahubConfigException("Vault secret '" + path + "' has no data");
        }
        return parsed.data().data();
    }

    private static String send(HttpRequest request, String operation) {
        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DatahubConfigException(operation + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatahubConfigException(operation + " interrupted", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DatahubConfigException(
                    operation + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static void requireSet(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new DatahubConfigException(what + " is required");
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    // KV v2 read: { "data": { "data": { <secret fields> }, "metadata": { … } } }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VaultResponse(VaultData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VaultData(Map<String, String> data) {
    }

    // AppRole login: { "auth": { "client_token": "…", "lease_duration": … } }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LoginResponse(Auth auth) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Auth(@JsonProperty("client_token") String clientToken) {
    }
}
