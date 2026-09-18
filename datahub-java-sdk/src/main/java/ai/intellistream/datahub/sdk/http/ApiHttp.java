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

    /**
     * The API answers a failure with {@code application/problem+json} (RFC 9457), a different media
     * type from the {@code application/json} it answers a success with. Both are asked for, so a
     * strict negotiation cannot refuse the very answer that says what went wrong.
     */
    private static final String ACCEPT = "application/json, application/problem+json";

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

    /** A request whose response body is ignored (e.g. delete/void endpoints). */
    public void send(String method, String path, Object body) {
        exchange(method, path, body, null);
    }

    /** PUT with a JSON body — e.g. replacing a settings document. */
    public <T> T put(String path, Object body, JavaType responseType) {
        return exchange("PUT", path, body, responseType);
    }

    /**
     * POST a raw byte body under an explicit content type — e.g. streaming a graph export file
     * back into {@code /resources/import}, which consumes {@code application/octet-stream}.
     */
    public <T> T postBytes(String path, byte[] body, String contentType, JavaType responseType) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .header("Accept", ACCEPT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return parse(sendString(request, "POST", path), responseType, "POST", path);
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
            throw DatahubApiException.of(status, "GET", path,
                    new String(response.body(), StandardCharsets.UTF_8), retryAfter(response));
        }
        return response.body();
    }

    /** PUT with a raw byte body and extra headers — e.g. a file upload (metadata travels in headers). */
    public <T> T put(String path, byte[] body, Map<String, String> headers, JavaType responseType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .header("Accept", ACCEPT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        return parse(sendString(builder.build(), "PUT", path), responseType, "PUT", path);
    }

    /** POST with a raw byte body under an explicit media type; the response body is ignored. */
    public void postBytes(String path, byte[] body, String contentType, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .header("Accept", ACCEPT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        parse(sendString(builder.build(), "POST", path), null, "POST", path);
    }

    private <T> T exchange(String method, String path, Object body, JavaType responseType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .header("Accept", ACCEPT);
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
            throw DatahubApiException.of(status, method, path, body, retryAfter(response));
        }
        if (responseType == null || status == 204 || body == null || body.isBlank()) {
            return null;
        }
        return mapper.readValue(body, responseType);
    }

    /**
     * The {@code Retry-After} delay in seconds, or -1. Only the delta-seconds form is read: it is
     * the one the API sends, and an HTTP-date would need a clock this client cannot trust against
     * the server's.
     */
    private static long retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException httpDateOrGarbage) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }
}
