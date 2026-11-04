// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.client.DatahubConfigException;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two hooks for a caller that manages its own credential and its own deadlines: a token
 * supplier asked on every request, and a timeout on every request.
 */
class RequestHooksTest {

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile long delayMillis;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void theTokenSupplierIsAskedOnEveryRequest() {
        AtomicInteger issued = new AtomicInteger();
        DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                .baseUrl(baseUrl())
                .tokenSupplier(() -> "tok-" + issued.incrementAndGet())
                .build());

        client.datasets().list(10);
        client.datasets().list(10);

        assertEquals(List.of("Bearer tok-1", "Bearer tok-2"), authorizations);
    }

    @Test
    void aSupplierWithNoTokenFailsTheRequestWithoutSendingIt() {
        DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                .baseUrl(baseUrl())
                .tokenSupplier(() -> null)
                .build());

        DatahubApiException e = assertThrows(DatahubApiException.class, () -> client.datasets().list(10));
        assertEquals(0, e.statusCode());
        assertTrue(authorizations.isEmpty());
    }

    @Test
    void theTokenSupplierStandsAlone() {
        DatahubConfig.Builder withToken = DatahubConfig.builder()
                .baseUrl(baseUrl()).token("static").tokenSupplier(() -> "t");
        DatahubConfig.Builder withClient = DatahubConfig.builder()
                .baseUrl(baseUrl()).clientCredentials("id", "secret", baseUrl() + "/token")
                .tokenSupplier(() -> "t");

        assertThrows(DatahubConfigException.class, withToken::build);
        assertThrows(DatahubConfigException.class, withClient::build);
    }

    @Test
    void aRequestThatOutlastsTheTimeoutFailsWithStatusZero() {
        delayMillis = 2_000;
        DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                .baseUrl(baseUrl())
                .token("t")
                .requestTimeout(Duration.ofMillis(200))
                .build());

        long started = System.nanoTime();
        DatahubApiException e = assertThrows(DatahubApiException.class, () -> client.datasets().list(10));
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(0, e.statusCode());
        assertTrue(tookMillis < 1_500, "gave up after " + tookMillis + " ms");
    }

    @Test
    void aTimeoutMustBePositive() {
        DatahubConfig.Builder zero = DatahubConfig.builder()
                .baseUrl(baseUrl()).token("t").requestTimeout(Duration.ZERO);

        assertThrows(DatahubConfigException.class, zero::build);
    }
}
