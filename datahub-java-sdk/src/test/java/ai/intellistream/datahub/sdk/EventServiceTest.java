// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.ingest.IngestOptions;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventServiceTest {

    @Test
    void filterReturnsEvents() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[{\"id\":\"evt-1\",\"externalId\":\"door.open\"}]}";
        server.createContext("/events/filter", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            DataWrapper<EventModel> result = client(server).events().filter(new EventRetreiver());
            assertEquals(1, result.getItems().size());
            assertEquals("evt-1", result.getItems().iterator().next().getId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ingestEventsChunksIntoBatches() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events/create", exchange -> {
            requests.incrementAndGet();
            // Drain before replying, and keep doing so. An HTTP/1.1 connection can only be kept
            // alive if the request body was consumed, so replying without reading it forces the
            // server to close the connection; the client's pool then reuses a closed connection and
            // the next batch fails with EOF before any response byte. That is retryable, so it is
            // sent again and the request count comes out one higher than the batching maths
            // predicts. See TimeseriesIngestTest, where the same omission produced an intermittent
            // failure that read as noise.
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] bytes = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            List<EventModel> events = new ArrayList<>();
            for (int i = 0; i < 2_500; i++) {
                EventModel event = new EventModel();
                event.setEventTime(1_700_000_000_000L + i); // event_time is required
                events.add(event);
            }
            IngestResult result = client(server).events().ingest(events,
                    IngestOptions.builder().batchSize(1_000).parallelism(4).build());

            assertEquals(2_500, result.succeeded());
            assertTrue(result.isComplete());
            assertEquals(3, requests.get()); // 1000 + 1000 + 500
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ingestRejectsEventWithoutEventTime() {
        DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:1")
                .token("test-token")
                .build());
        EventModel event = new EventModel();
        event.setExternalId("door_open");
        // no eventTime set -> rejected before any request is made
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.events().ingest(List.of(event)));
        assertTrue(ex.getMessage().contains("eventTime"), ex.getMessage());
    }

    private static DatahubClient client(HttpServer server) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }
}
