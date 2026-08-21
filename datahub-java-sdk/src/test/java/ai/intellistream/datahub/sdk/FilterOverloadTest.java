// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The criteria-only {@code filter(...)} overloads on resources and events, which datasets and
 * timeseries already had.
 *
 * <p>What is worth pinning is that they wrap the criteria in a retriever rather than posting the
 * filter bare: the endpoints read {@code {"filter": {...}, "limit": n}}, so a bare filter body
 * deserialises into a retriever with every criterion defaulted — which matches everything instead
 * of failing, and looks like the filter was ignored.
 */
class FilterOverloadTest {

    @Test
    void resourceCriteriaAreWrappedInARetriever() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = serverEchoing("/resources/filter", received);
        try {
            ResourceFilter criteria = new ResourceFilter();
            criteria.setNodeType(List.of("asset"));
            criteria.setIsRoot(true);

            clientFor(server).resources().filter(criteria);

            String sent = received.get();
            assertTrue(sent.contains("\"filter\":{"), sent);
            assertTrue(sent.contains("\"nodeType\":[\"asset\"]"), sent);
            assertTrue(sent.contains("\"isRoot\":true"), sent);
            assertTrue(sent.contains("\"limit\":1000"), sent); // retriever default rides along
        } finally {
            server.stop(0);
        }
    }

    @Test
    void eventCriteriaAreWrappedInARetriever() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = serverEchoing("/events/filter", received);
        try {
            EventFilter criteria = new EventFilter();
            criteria.setType(List.of("Alarm", "Warning")); // a pattern list, not one exact string
            criteria.setStatus(List.of("OPEN"));

            clientFor(server).events().filter(criteria);

            String sent = received.get();
            assertTrue(sent.contains("\"filter\":{"), sent);
            assertTrue(sent.contains("\"type\":[\"Alarm\",\"Warning\"]"), sent);
            assertTrue(sent.contains("\"status\":[\"OPEN\"]"), sent);
            assertTrue(sent.contains("\"limit\":1000"), sent);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer serverEchoing(String path, AtomicReference<String> received) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(buffer);
            received.set(buffer.toString(StandardCharsets.UTF_8));

            byte[] bytes = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
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
}
