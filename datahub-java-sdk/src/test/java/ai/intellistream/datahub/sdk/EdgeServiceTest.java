// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.RelationshipType;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.resource.RelTypeForm;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeServiceTest {

    @Test
    void findByIdDeserializesTheEdgeAndCoercesStringIds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // id and relationshipTypeId cross the wire as JSON strings (JS-safe); start/end are numbers.
        String body = "{\"items\":[{\"id\":\"341\",\"start\":5677892,\"end\":5677893,"
                + "\"type\":\"FLOWS_TO\",\"relationshipTypeId\":\"88\"}]}";
        respondJson(server, "/edges/341", 200, body, null);
        server.start();
        try {
            DataWrapper<EdgeProxy> result = client(server).edges().findById(341);

            assertEquals(1, result.getItems().size());
            EdgeProxy edge = result.getItems().iterator().next();
            assertEquals(341L, edge.getId());            // coerced from the JSON string "341"
            assertEquals(5677892L, edge.getStart());
            assertEquals(5677893L, edge.getEnd());
            assertEquals("FLOWS_TO", edge.getType());
            assertEquals(88L, edge.getRelationshipTypeId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void byIdsReturnsTheEdgesWithTheirEndpointNodes() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"nodes\":[{\"id\":\"5677892\",\"externalId\":\"plant_oslo\",\"name\":\"Plant Oslo\"}],"
                + "\"relations\":[{\"id\":\"341\",\"start\":5677892,\"end\":5677893,\"type\":\"CONTAINS\"}]}";
        respondJson(server, "/edges/byids", 200, body, requestBody);
        server.start();
        try {
            IdCollection id = new IdCollection();
            id.setId(341L);
            GraphDataWrapper<Resource, EdgeProxy> result = client(server).edges().byIds(List.of(id));

            assertTrue(requestBody.get().contains("\"items\""), requestBody.get());
            assertEquals(1, result.getNodes().size());
            assertEquals("plant_oslo", result.getNodes().iterator().next().getExternalId());
            assertEquals(1, result.getRelations().size());
            assertEquals(341L, result.getRelations().iterator().next().getId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createPostsTheRelationFormsAndReadsBackTheEdges() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[{\"id\":\"341\",\"start\":5677892,\"end\":5677893,"
                + "\"type\":\"CONTAINS\",\"description\":\"Feeds the east wing\"}]}";
        respondJson(server, "/edges/create", 201, body, requestBody);
        server.start();
        try {
            RelForm contains = new RelForm();
            contains.setFromExternalId("plant_oslo");
            contains.setToExternalId("pump_1");
            contains.setRelationshipType("CONTAINS");

            DataWrapper<EdgeProxy> result = client(server).edges().create(List.of(contains));

            // The relation forms go up under "items", not the "relations" of the graph create.
            assertTrue(requestBody.get().contains("\"items\""), requestBody.get());
            assertTrue(requestBody.get().contains("\"fromExternalId\":\"plant_oslo\""), requestBody.get());
            assertTrue(requestBody.get().contains("\"toExternalId\":\"pump_1\""), requestBody.get());

            assertEquals(1, result.getItems().size());
            EdgeProxy edge = result.getItems().iterator().next();
            assertEquals(341L, edge.getId());            // coerced from the JSON string "341"
            assertEquals(5677892L, edge.getStart());
            assertEquals(5677893L, edge.getEnd());
            assertEquals("CONTAINS", edge.getType());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void typesDeserializesTheRelationshipTypeCatalog() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[{\"id\":\"88\",\"name\":\"FLOWS_TO\",\"description\":\"Flow direction\"}]}";
        respondJson(server, "/edges/types", 200, body, null);
        server.start();
        try {
            DataWrapper<RelationshipType> result = client(server).edges().types();

            assertEquals(1, result.getItems().size());
            RelationshipType type = result.getItems().iterator().next();
            assertEquals(88L, type.getId());             // coerced from the JSON string "88"
            assertEquals("FLOWS_TO", type.getName());
            assertEquals("Flow direction", type.getDescription());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createTypesNormalisesTheNameAndReadsBackTheType() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[{\"id\":\"88\",\"name\":\"FLOWS_TO\"}]}";
        respondJson(server, "/edges/types/create", 200, body, requestBody);
        server.start();
        try {
            RelTypeForm form = new RelTypeForm();
            form.setName("Flows To");                    // normalised to FLOWS_TO client-side by the form

            DataWrapper<RelationshipType> result = client(server).edges().createTypes(List.of(form));

            assertTrue(requestBody.get().contains("\"items\""), requestBody.get());
            assertTrue(requestBody.get().contains("\"name\":\"FLOWS_TO\""), requestBody.get());
            assertEquals("FLOWS_TO", result.getItems().iterator().next().getName());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deleteSendsTheIdsAndIgnoresTheEmptyBody() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/edges/delete", exchange -> {
            method.set(exchange.getRequestMethod());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);       // 204 No Content, no body
            exchange.close();
        });
        server.start();
        try {
            IdCollection id = new IdCollection();
            id.setId(341L);
            client(server).edges().delete(List.of(id));  // returns void; must not throw on the empty body

            assertEquals("DELETE", method.get());
            assertTrue(requestBody.get().contains("\"items\""), requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    private static void respondJson(HttpServer server, String path, int status, String body,
                                    AtomicReference<String> requestBodySink) {
        server.createContext(path, exchange -> {
            if (requestBodySink != null) {
                requestBodySink.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private static DatahubClient client(HttpServer server) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }
}
