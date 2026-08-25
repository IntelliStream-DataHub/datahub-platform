// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceServiceTest {

    @Test
    void getByIdDeserializesResourcesAndCoercesStringId() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The API serializes the 64-bit id as a JSON *string* (JS-safe); the SDK must read it back into a Long.
        String body = "{\"items\":[{\"id\":\"5677892\",\"externalId\":\"pump_1\",\"name\":\"Pump 1\"}]}";
        server.createContext("/resources/1", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            DatahubClient client = client(server);

            DataWrapper<ai.intellistream.datahub.models.NodeModel> result = client.resources().getById(1);

            assertEquals(1, result.getItems().size());
            // No type-label in the fixture, so the node binds as a plain Resource.
            Resource r = (Resource) result.getItems().iterator().next();
            assertEquals(5677892L, r.getId());          // coerced from the JSON string "5677892"
            assertEquals("pump_1", r.getExternalId());
            assertEquals("Pump 1", r.getName());
            assertEquals("Bearer test-token", authHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonSuccessStatusThrowsWithBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resources/9", exchange -> {
            byte[] bytes = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            DatahubClient client = client(server);
            DatahubApiException ex = assertThrows(DatahubApiException.class, () -> client.resources().getById(9));
            assertEquals(404, ex.statusCode());
            assertEquals("{\"error\":\"not found\"}", ex.body());
        } finally {
            server.stop(0);
        }
    }

    private static DatahubClient client(HttpServer server) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }
}
