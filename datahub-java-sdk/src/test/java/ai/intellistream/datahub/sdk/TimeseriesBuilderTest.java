// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.ingest.IngestOptions;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import ai.intellistream.datahub.sdk.timeseries.Datapoint;
import ai.intellistream.datahub.timeseries.Timeseries;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeseriesBuilderTest {

    @Test
    void datapointOfBuildsWireForm() {
        DatapointString a = Datapoint.of(Instant.ofEpochMilli(1000), 1.5).toDatapointString();
        assertEquals("1000", a.getTimestamp());
        assertEquals("1.5", a.getValue());

        DatapointString b = Datapoint.of(2000L, 3.0).toDatapointString(); // epoch-millis overload
        assertEquals("2000", b.getTimestamp());
        assertEquals("3.0", b.getValue());

        assertEquals("N/A", Datapoint.of(Instant.ofEpochMilli(0), "N/A").toDatapointString().getValue());
    }

    @Test
    void timeseriesStoresExternalIdVerbatimButStillCanonicalizesTheUnit() {
        Timeseries ts = new Timeseries();
        ts.setExternalId("Engine.Temperature");
        ts.setUnitExternalId("Deg C");
        ts.setName("Engine temp");

        // The series' own external id is the caller's identifier and is sent exactly as given, so a
        // byte-equality join against the system that issued it keeps working.
        assertEquals("Engine.Temperature", ts.getExternalId());
        // The unit external id is NOT the caller's identifier — it names an entry in the platform's
        // shipped unit catalogue, whose ids are snake_case by construction — so it is still
        // canonicalized, and the lookup canonicalizes the same way.
        assertEquals("deg_c", ts.getUnitExternalId());
        assertEquals("Engine temp", ts.getName());
    }

    @Test
    void createVarargsSendsTheExternalIdVerbatim() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/create", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            Timeseries ts = new Timeseries();
            ts.setExternalId("Engine.Temperature");
            ts.setUnitExternalId("Celsius");
            client(server).timeseries().create(ts);
            // Verbatim on the wire: no rewrite between the setter and the request body.
            assertTrue(body.get().contains("Engine.Temperature"), body.get());
            assertTrue(body.get().contains("celsius"), body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ingestByExternalIdChunksAndSends() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/data", exchange -> {
            requests.incrementAndGet();
            byte[] resp = "{\"items\":[\"ok\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            IngestResult result = client(server).timeseries().ingest(
                    Map.of("engine_temperature", List.of(
                            Datapoint.of(1_700_000_000_000L, 92.4),
                            Datapoint.of(Instant.ofEpochMilli(1_700_000_001_000L), 92.6))),
                    IngestOptions.builder().batchSize(1).parallelism(2).build());

            assertEquals(2, result.succeeded()); // 2 datapoints, batch size 1 -> 2 requests
            assertTrue(result.isComplete());
            assertEquals(2, requests.get());
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
