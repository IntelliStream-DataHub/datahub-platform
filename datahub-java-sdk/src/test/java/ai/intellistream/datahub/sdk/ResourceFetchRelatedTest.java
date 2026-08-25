// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceFetchRelatedTest {

    @Test
    void fetchRelatedReturnsTheConnectedSubgraph() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // sensor_a and sensor_b are both PART_OF the cooling_system — the network that proves it.
        String network = """
            {
              "nodes": [
                {"id":"1","externalId":"cooling_system","name":"Cooling system"},
                {"id":"2","externalId":"sensor_a","name":"Sensor A"},
                {"id":"3","externalId":"sensor_b","name":"Sensor B"}
              ],
              "edges": [
                {"id":"10","start":2,"end":1,"type":"PART_OF"},
                {"id":"11","start":3,"end":1,"type":"PART_OF"}
              ],
              "labels": [
                {"id":"1","name":"SYSTEM"}
              ]
            }""";
        server.createContext("/resources/fetch-related", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = network.getBytes(StandardCharsets.UTF_8);
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

            ResourceNetwork result = client.resources().fetchRelated("sensor_a", 3);

            // the request carried the starting node and the depth bound
            assertTrue(requestBody.get().contains("sensor_a"), requestBody.get());
            assertTrue(requestBody.get().contains("3"), requestBody.get());

            // the sub-graph deserialized: 3 nodes, 2 edges, 1 label
            assertEquals(3, result.nodes().size());
            assertEquals(2, result.edges().size());
            assertEquals(1, result.labels().size());

            // ids came back as JSON strings and were coerced to Long
            Set<String> externalIds = result.nodes().stream()
                    .map(ai.intellistream.datahub.models.NodeModel::getExternalId).collect(Collectors.toSet());
            assertTrue(externalIds.contains("cooling_system"));
            assertTrue(externalIds.contains("sensor_a"));
            assertTrue(externalIds.contains("sensor_b"));

            // both sensors' edges point at the same node — their shared subsystem
            long coolingId = result.nodes().stream()
                    .filter(n -> "cooling_system".equals(n.getExternalId()))
                    .findFirst().orElseThrow().getId();
            Set<Long> edgeTargets = result.edges().stream()
                    .map(EdgeProxy::getEnd).collect(Collectors.toSet());
            assertEquals(Set.of(coolingId), edgeTargets);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchRelatedFormCarriesDepthAndRelationshipFilter() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resources/fetch-related", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"nodes\":[],\"edges\":[],\"labels\":[]}".getBytes(StandardCharsets.UTF_8);
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

            RelatedResourcesForm form = new RelatedResourcesForm();
            form.setExternalId("pump_esp_a12");
            form.setDepth(5);
            form.setRelationshipTypes(java.util.List.of("PART_OF"));

            ResourceNetwork empty = client.resources().fetchRelated(form);

            assertTrue(empty.nodes().isEmpty());
            assertTrue(requestBody.get().contains("pump_esp_a12"));
            assertTrue(requestBody.get().contains("PART_OF"));
        } finally {
            server.stop(0);
        }
    }
}
