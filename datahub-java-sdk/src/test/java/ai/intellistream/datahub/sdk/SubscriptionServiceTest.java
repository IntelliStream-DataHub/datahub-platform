// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.subscription.SubscriptionRetriever;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionServiceTest {

    @Test
    void listReturnsSubscriptions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[{\"id\":\"5\",\"externalId\":\"sub-1\",\"name\":\"My Sub\"}]}";
        server.createContext("/subscriptions/list", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .build());

            DataWrapper<Subscription> result = client.subscriptions().list(new SubscriptionRetriever());

            assertEquals(1, result.getItems().size());
            Subscription subscription = result.getItems().iterator().next();
            assertEquals(5L, subscription.getId());
            assertEquals("sub-1", subscription.getExternalId());
            assertEquals("My Sub", subscription.getName());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createSurfaces403WhenServerDeniesDatasetAccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The server refuses the create because the caller can't read the timeseries' dataset.
        String problem = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                + "\"detail\":\"No read permission for data set: 9\"}";
        server.createContext("/subscriptions/create", exchange -> {
            byte[] bytes = problem.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(403, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .build());

            Subscription sub = new Subscription();
            sub.setExternalId("sub-secret");
            sub.setName("Secret Sub");
            sub.setTimeseries(List.of(IdCollection.createFromExternalId("secret_ts")));

            DatahubApiException ex = assertThrows(DatahubApiException.class,
                    () -> client.subscriptions().create(List.of(sub)));
            assertEquals(403, ex.statusCode());
        } finally {
            server.stop(0);
        }
    }
}
