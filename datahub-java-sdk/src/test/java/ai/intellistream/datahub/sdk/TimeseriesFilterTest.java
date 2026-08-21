// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.TimeseriesRetreiver;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.timeseries.Timeseries;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeseriesFilterTest {

    @Test
    void filterPostsCriteriaAndParsesItems() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        String body = "{\"items\":[{\"id\":\"7\",\"externalId\":\"temp_room_a\",\"name\":\"Temp Room A\"," +
                "\"unit\":\"bar\",\"dataSetId\":\"12\"}]}";
        server.createContext("/timeseries/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

            TimeseriesFilter criteria = new TimeseriesFilter();
            criteria.setDataSetId(java.util.List.of(IdCollection.createFromId(12L)));
            criteria.setUnit(java.util.List.of("bar"));
            criteria.setMetadata(java.util.Collections.singletonMap("sensor_vendor", null));
            DataWrapper<Timeseries> result = client.timeseries().filter(criteria);

            assertEquals(1, result.getItems().size());
            Timeseries ts = result.getItems().iterator().next();
            assertEquals("temp_room_a", ts.getExternalId());
            assertEquals(12L, ts.getDataSetId());          // coerced from the JSON string "12"

            String sent = requestBody.get();
            assertTrue(sent.contains("\"dataSetId\":[{\"id\":\"12\"}]"), sent); // ToStringSerializer writes ids as strings
            assertTrue(sent.contains("\"unit\":[\"bar\"]"), sent);
            // A null value asks for the key alone — what metadataKey used to say.
            assertTrue(sent.contains("\"sensor_vendor\":null"), sent);
            assertTrue(sent.contains("\"limit\":1000"), sent);       // retriever default rides along
        } finally {
            server.stop(0);
        }
    }

    @Test
    void filterWithRetrieverSendsExplicitLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/timeseries/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
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

            TimeseriesRetreiver request = new TimeseriesRetreiver();
            request.setLimit(25);
            request.getFilter().setUnitExternalId(java.util.List.of("temperature_deg_c"));
            DataWrapper<Timeseries> result = client.timeseries().filter(request);

            assertEquals(0, result.getItems().size());
            String sent = requestBody.get();
            assertTrue(sent.contains("\"limit\":25"), sent);
            assertTrue(sent.contains("\"unitExternalId\":[\"temperature_deg_c\"]"), sent);
        } finally {
            server.stop(0);
        }
    }
}
