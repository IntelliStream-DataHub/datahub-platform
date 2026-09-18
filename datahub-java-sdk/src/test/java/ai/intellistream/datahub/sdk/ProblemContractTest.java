// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.errors.Problem;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a caller gets when the API refuses: the RFC 9457 problem document, read.
 *
 * <p>The bodies are the shape the API emits — {@code type} as the member to branch on, extension
 * members at the top level, {@code requestId} and {@code retry} on every one of them.
 */
class ProblemContractTest {

    @Test
    @DisplayName("a refusal arrives as a problem, not as a body the caller has to parse")
    void readsTheProblemDocument() throws Exception {
        String body = """
                {"type":"https://intellistream.ai/errors/validation-failed",\
                "title":"Validation failed","status":400,\
                "detail":"One or more fields are invalid.","instance":"/timeseries/1",\
                "fields":[{"field":"externalId","message":"must not be blank",\
                "code":"NotBlank","rejected":null}],\
                "requestId":"0199f2a4-6c1e-7b3a-9d4f-2e8c5a1b7d90","retry":"change-request"}""";
        HttpServer server = serve("/timeseries/1", 400, body, null);
        try {
            DatahubClient client = client(server);

            DatahubApiException ex = assertThrows(DatahubApiException.class,
                    () -> client.timeseries().getById(1));

            Problem problem = ex.problem();
            assertEquals("validation-failed", problem.slug());
            assertEquals(400, problem.status());
            assertEquals("0199f2a4-6c1e-7b3a-9d4f-2e8c5a1b7d90", problem.requestId());
            assertEquals(Problem.RETRY_CHANGE_REQUEST, problem.retry());
            assertEquals("externalId", problem.fields().get(0).field());
            // The message says what the API said, not only which status it said it with.
            assertTrue(ex.getMessage().contains("Validation failed"), ex.getMessage());
            assertTrue(ex.getMessage().contains("externalId: must not be blank"), ex.getMessage());
            assertTrue(ex.getMessage().contains("requestId=0199f2a4"), ex.getMessage());
            // The raw body stays available for anything the typed view leaves out.
            assertEquals(body, ex.body());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("an answer that is not a problem document still yields one, and nothing throws")
    void toleratesANonProblemBody() throws Exception {
        // What a load balancer or a sidecar answers when the application never saw the request.
        HttpServer server = serve("/timeseries/1", 502, "<html><body>502 Bad Gateway</body></html>", null);
        try {
            DatahubApiException ex = assertThrows(DatahubApiException.class,
                    () -> client(server).timeseries().getById(1));

            assertEquals(502, ex.statusCode());
            assertEquals(502, ex.problem().status());
            assertNull(ex.problem().slug());
            assertEquals(-1, ex.retryAfterSeconds());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Retry-After is carried, so a backoff need not guess shorter than the server asked")
    void carriesRetryAfter() throws Exception {
        String body = """
                {"type":"https://intellistream.ai/errors/rate-limit-exceeded","title":"Too Many Requests",\
                "status":429,"detail":"Too many requests.","retry":"same-request"}""";
        HttpServer server = serve("/timeseries/1", 429, body, "7");
        try {
            DatahubApiException ex = assertThrows(DatahubApiException.class,
                    () -> client(server).timeseries().getById(1));

            assertEquals(7, ex.retryAfterSeconds());
            assertTrue(ex.problem().retryable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("the client asks for problem+json as well as json")
    void acceptsProblemJson() throws Exception {
        // The API labels a failure application/problem+json, a different media type from the
        // application/json a success carries. Asking only for the latter invites a 406 in place of
        // the very answer that says what went wrong.
        AtomicReference<String> accept = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/1", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            write(exchange, 200, "{\"items\":[]}", null);
        });
        server.start();
        try {
            client(server).timeseries().getById(1);
            assertTrue(accept.get().contains("application/problem+json"), accept.get());
            assertTrue(accept.get().contains("application/json"), accept.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer serve(String path, int status, String body, String retryAfter) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> write(exchange, status, body, retryAfter));
        server.start();
        return server;
    }

    private static void write(HttpExchange exchange, int status, String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                status >= 400 ? "application/problem+json" : "application/json");
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static DatahubClient client(HttpServer server) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }
}
