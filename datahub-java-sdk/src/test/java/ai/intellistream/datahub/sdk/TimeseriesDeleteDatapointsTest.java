// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeseriesDeleteDatapointsTest {

    /** Answers 204 like the real endpoint, and records what was posted to it. */
    private static HttpServer acceptingServer(AtomicReference<String> requestBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/data/delete", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static DatahubClient clientFor(HttpServer server) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }

    @Test
    void instantBoundsGoOutAsIso8601UnderTheApiFieldNames() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = acceptingServer(requestBody);
        try {
            clientFor(server).timeseries().deleteDatapoints(
                    "engine_temperature",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-02-01T00:00:00Z"));

            String sent = requestBody.get();
            assertTrue(sent.contains("\"externalId\":\"engine_temperature\""), sent);
            // The api rejects unknown fields, so start/end (what the endpoint doc used to call
            // these) would be a 400 rather than a wider delete.
            assertTrue(sent.contains("\"inclusiveBegin\":\"2026-01-01T00:00:00Z\""), sent);
            assertTrue(sent.contains("\"exclusiveEnd\":\"2026-02-01T00:00:00Z\""), sent);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void twoNullBoundsClearTheWholeSeries() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = acceptingServer(requestBody);
        try {
            clientFor(server).timeseries().deleteDatapoints("engine_temperature", null, null);

            // An open bound has to travel as null, not as an instant the SDK made up: null on both
            // sides is what the api reads as "every datapoint of this series".
            String sent = requestBody.get();
            assertTrue(sent.contains("\"inclusiveBegin\":null"), sent);
            assertTrue(sent.contains("\"exclusiveEnd\":null"), sent);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void severalSeriesEachKeepTheirOwnWindow() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = acceptingServer(requestBody);
        try {
            DeleteDatapoint byExternalId = new DeleteDatapoint();
            byExternalId.setExternalId("engine_temperature");
            byExternalId.setInclusiveBegin("2026-01-01T00:00:00Z");

            DeleteDatapoint byId = new DeleteDatapoint();
            byId.setId(7L);
            byId.setExclusiveEnd("1767225600000");   // epoch millis is the other accepted form

            clientFor(server).timeseries().deleteDatapoints(List.of(byExternalId, byId));

            String sent = requestBody.get();
            assertTrue(sent.contains("\"items\":["), sent);
            assertTrue(sent.contains("\"id\":\"7\""), sent);   // ids travel as strings
            assertTrue(sent.contains("\"exclusiveEnd\":\"1767225600000\""), sent);
            assertFalse(sent.contains("\"externalId\":\"engine_temperature\",\"inclusiveBegin\":null"), sent);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRejectedWindowSurfacesAsAnException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String error = "{\"error\":{\"code\":400,\"message\":\"'last tuesday' is not a valid inclusiveBegin.\"}}";
        server.createContext("/timeseries/data/delete", exchange -> {
            byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            DeleteDatapoint window = new DeleteDatapoint();
            window.setExternalId("engine_temperature");
            window.setInclusiveBegin("last tuesday");

            // The api validates bounds at its own boundary now, so a bad one is a 400 the caller
            // sees rather than a 204 followed by a message that dead-letters unnoticed.
            DatahubApiException thrown = assertThrows(DatahubApiException.class,
                    () -> clientFor(server).timeseries().deleteDatapoints(window));

            assertEquals(400, thrown.statusCode());
            assertTrue(thrown.body().contains("inclusiveBegin"), thrown.body());
        } finally {
            server.stop(0);
        }
    }
}
