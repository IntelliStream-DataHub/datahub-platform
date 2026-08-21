// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetServiceTest {

    @Test
    void byIdsReturnsDatasets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[{\"id\":\"42\",\"externalId\":\"plant_a\",\"name\":\"Plant A\"}]}";
        server.createContext("/datasets/byids", exchange -> {
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

            DataWrapper<DataSetModel> result = client.datasets().byIds(List.of(IdCollection.createFromId(42)));

            assertEquals(1, result.getItems().size());
            DataSetModel dataset = result.getItems().iterator().next();
            assertEquals(42L, dataset.getId());           // coerced from the JSON string "42"
            assertEquals("plant_a", dataset.getExternalId());
            assertEquals("Plant A", dataset.getName());
        } finally {
            server.stop(0);
        }
    }
    /**
     * The filter used to be a silent no-op: the SDK serialised it faithfully and the server threw it
     * away. This pins the half the SDK owns — that the criteria reach {@code /datasets/filter} as
     * the request body rather than being dropped on the way out.
     */
    @Test
    void filterPostsCriteriaToTheFilterEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        String body = "{\"items\":[{\"id\":\"7\",\"externalId\":\"sap_work_orders\",\"name\":\"SAP work orders\"}]}";
        server.createContext("/datasets/filter", exchange -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(buffer);
            received.set(buffer.toString(StandardCharsets.UTF_8));

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

            DataSetFilter criteria = new DataSetFilter();
            criteria.setExternalId(java.util.List.of("sap_*"));
            criteria.setMetadata(Map.of("owner", "plant-a"));

            DataWrapper<DataSetModel> result = client.datasets().filter(criteria);

            assertEquals(1, result.getItems().size());
            assertEquals("sap_work_orders", result.getItems().iterator().next().getExternalId());

            String sent = received.get();
            assertTrue(sent.contains("\"externalId\":[\"sap_*\"]"), sent);
            assertTrue(sent.contains("\"owner\":\"plant-a\""), sent);
        } finally {
            server.stop(0);
        }
    }
}
