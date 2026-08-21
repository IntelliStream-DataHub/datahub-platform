// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.http;

import ai.intellistream.datahub.sdk.auth.TokenProvider;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The shared HTTP request core every service goes through: attaches the bearer token,
 * (de)serializes JSON via Jackson, and maps non-2xx responses to {@link DatahubApiException}.
 * Thread-safe and meant to be shared.
 */
public final class ApiHttp {

    private final String baseUrl;
    private final HttpClient http;
    private final JsonMapper mapper;
    private final TokenProvider tokenProvider;

    public ApiHttp(String baseUrl, HttpClient http, JsonMapper mapper, TokenProvider tokenProvider) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.mapper = mapper;
        this.tokenProvider = tokenProvider;
    }

    /** Jackson type factory, for services to build parametric types like {@code DataWrapper<Resource>}. */
    public TypeFactory typeFactory() {
        return mapper.getTypeFactory();
    }

    /** The shared HttpClient — used by the subscription WebSocket listener. */
    public HttpClient httpClient() {
        return http;
    }

    /** Base URL (no trailing slash) — used to derive the WebSocket listen URL. */
    public String baseUrl() {
        return baseUrl;
    }

    /** A current bearer token (refreshed as needed). */
    public String token() {
        return tokenProvider.getToken();
    }

    /** The shared JSON mapper. */
    public JsonMapper mapper() {
        return mapper;
    }

    public <T> T get(String path, JavaType responseType) {
        return exchange("GET", path, null, responseType);
    }

    public <T> T post(String path, Object body, JavaType responseType) {
        return exchange("POST", path, body, responseType);
    }

    public <T> T delete(String path, Object body, JavaType responseType) {
        return exchange("DELETE", path, body, responseType);
    }

    /** A request whose response body is ignored (e.g. delete/void endpoints). */
    public void send(String method, String path, Object body) {
        exchange(method, path, body, null);
    }

    /** GET returning the raw response body — e.g. a file download. */
    public byte[] getBytes(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DatahubApiException(0, "GET " + path + " failed: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatahubApiException(0, "GET " + path + " interrupted", null);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new DatahubApiException(status, "HTTP " + status + " for GET " + path,
                    new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    /** PUT with a raw byte body and extra headers — e.g. a file upload (metadata travels in headers). */
    public <T> T put(String path, byte[] body, Map<String, String> headers, JavaType responseType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        return parse(sendString(builder.build(), "PUT", path), responseType, "PUT", path);
    }

    private <T> T exchange(String method, String path, Object body, JavaType responseType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return parse(sendString(builder.build(), method, path), responseType, method, path);
    }

    private HttpResponse<String> sendString(HttpRequest request, String method, String path) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DatahubApiException(0, method + " " + path + " failed: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatahubApiException(0, method + " " + path + " interrupted", null);
        }
    }

    private <T> T parse(HttpResponse<String> response, JavaType responseType, String method, String path) {
        int status = response.statusCode();
        String body = response.body();
        if (status < 200 || status >= 300) {
            throw new DatahubApiException(status, "HTTP " + status + " for " + method + " " + path, body);
        }
        if (responseType == null || status == 204 || body == null || body.isBlank()) {
            return null;
        }
        return mapper.readValue(body, responseType);
    }
}
