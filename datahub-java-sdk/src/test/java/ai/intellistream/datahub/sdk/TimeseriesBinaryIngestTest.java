// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.ingest.BinaryIngestBuffer;
import ai.intellistream.datahub.sdk.ingest.BinaryIngestOptions;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import ai.intellistream.datahub.sdk.timeseries.Datapoint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binary ingest against a loopback server that speaks the contract back: it answers
 * {@code /timeseries/byids} from a small catalogue and parses every frame it receives on
 * {@code /timeseries/data/binary} with the same reader the API uses, so what is asserted here is
 * what the API would accept.
 */
class TimeseriesBinaryIngestTest {

    record Received(int frames, long rows, List<DatapointFrame> parsed) {
    }

    final JsonMapper json = JsonMapper.builder().build();
    final Map<String, String[]> catalogue = new LinkedHashMap<>(); // externalId -> {id, valueType}
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger byIdsCalls = new AtomicInteger();
    final AtomicInteger binaryCalls = new AtomicInteger();
    final List<Integer> scriptedStatuses = new CopyOnWriteArrayList<>();
    String scriptedBody = "";
    String lastContentType;
    HttpServer server;
    DatahubClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/byids", exchange -> {
            byIdsCalls.incrementAndGet();
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            StringBuilder items = new StringBuilder();
            for (JsonNode item : request.path("items")) {
                String externalId = item.path("externalId").asString(null);
                String[] entry = externalId == null ? null : catalogue.get(externalId);
                if (entry != null) {
                    if (!items.isEmpty()) items.append(',');
                    items.append("{\"id\":\"").append(entry[0]).append("\",\"externalId\":\"").append(externalId)
                            .append("\",\"valueType\":\"").append(entry[1]).append("\"}");
                }
            }
            reply(exchange, 200, "{\"items\":[" + items + "]}");
        });
        server.createContext("/timeseries/data/binary", exchange -> {
            int n = binaryCalls.incrementAndGet();
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] body = exchange.getRequestBody().readAllBytes();
            int status = n <= scriptedStatuses.size() ? scriptedStatuses.get(n - 1) : 204;
            if (status == 204) {
                List<DatapointFrame> frames = DatapointFrame.parseAll(body, new ZstdPayloadCodec());
                long rows = 0;
                for (DatapointFrame f : frames) rows += f.rowCount();
                received.add(new Received(frames.size(), rows, frames));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else {
                reply(exchange, status, scriptedBody);
            }
        });
        server.start();
        client = DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static DatapointsCollection collection(String externalId, int n) {
        DatapointsCollection collection = new DatapointsCollection();
        collection.setExternalId(externalId);
        List<DatapointString> datapoints = new ArrayList<>(n);
        // Reverse order: the client must sort.
        for (int i = n - 1; i >= 0; i--) {
            datapoints.add(new DatapointString(String.valueOf(1_700_000_000_000L + i * 1000L), String.valueOf(i)));
        }
        collection.setDatapoints(datapoints);
        return collection;
    }

    @Test
    void framesArePackedUnderTheCapsAndSortedOnTheClient() {
        catalogue.put("engine.temp", new String[]{"11", "float32"});
        IngestResult result = client.timeseries().ingestBinary(List.of(collection("engine.temp", 250_000)),
                BinaryIngestOptions.builder().zstdLevel(1).build());

        assertTrue(result.isComplete(), result.toString());
        assertEquals(250_000, result.succeeded());
        assertEquals(1, binaryCalls.get(), "250k float32 points fit one request");
        assertEquals(1, byIdsCalls.get());
        assertEquals(FrameLimits.MEDIA_TYPE, lastContentType);
        Received r = received.get(0);
        assertEquals(3, r.frames(), "100k rows per numeric frame");
        assertEquals(250_000, r.rows());
        // Frames are cut in arrival order and sorted within themselves; the reversed input must come
        // out ascending in every frame, and the three frames together must cover the whole range.
        long earliest = Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;
        for (DatapointFrame f : r.parsed()) {
            assertEquals(DatapointValueType.FLOAT32, f.valueType());
            assertEquals(11L, f.seriesIds()[0]);
            assertEquals("engine.temp", f.externalIds()[0]);
            for (int row = 1; row < f.rowCount(); row++) {
                assertTrue(f.timestamp(row) > f.timestamp(row - 1), "sorted within frame " + f.index());
            }
            earliest = Math.min(earliest, f.timestamp(0));
            latest = Math.max(latest, f.timestamp(f.rowCount() - 1));
        }
        assertEquals(1_700_000_000_000L, earliest);
        assertEquals(1_700_000_000_000L + 249_999L * 1000L, latest);
    }

    @Test
    void eachSeriesTravelsInAFrameOfItsType() {
        catalogue.put("count", new String[]{"1", "bigint"});
        catalogue.put("state", new String[]{"2", "text"});
        catalogue.put("temp", new String[]{"3", "float32"});
        Map<String, List<Datapoint>> points = new LinkedHashMap<>();
        Instant t = Instant.ofEpochMilli(1_700_000_000_000L);
        points.put("count", List.of(Datapoint.of(t, 42L)));
        points.put("state", List.of(Datapoint.of(t, "open")));
        points.put("temp", List.of(Datapoint.of(t, 21.5)));

        IngestResult result = client.timeseries().ingestBinary(points);

        assertTrue(result.isComplete(), result.toString());
        assertEquals(3, result.succeeded());
        Received r = received.get(0);
        assertEquals(3, r.frames());
        Map<DatapointValueType, String> values = new LinkedHashMap<>();
        for (DatapointFrame f : r.parsed()) values.put(f.valueType(), f.valueAsString(0));
        assertEquals("42", values.get(DatapointValueType.BIGINT));
        assertEquals("open", values.get(DatapointValueType.TEXT));
        assertEquals("21.5", values.get(DatapointValueType.FLOAT32));
    }

    @Test
    void aValueThatDoesNotFitItsTypeIsALocalError() {
        catalogue.put("temp", new String[]{"3", "float32"});
        DatapointsCollection c = collection("temp", 3);
        c.getDatapoints().add(new DatapointString("1700000010000", "not-a-number"));

        IngestResult result = client.timeseries().ingestBinary(List.of(c));

        assertFalse(result.isComplete());
        assertEquals(3, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(422, result.errors().get(0).statusCode());
        assertEquals(1, binaryCalls.get());
    }

    @Test
    void anUnknownSeriesIsALocalErrorAndSendsNothing() {
        IngestResult result = client.timeseries().ingestBinary(List.of(collection("ghost", 5)));

        assertEquals(0, result.succeeded());
        assertEquals(5, result.failed());
        assertEquals(404, result.errors().get(0).statusCode());
        assertEquals(0, binaryCalls.get());
    }

    @Test
    void transientFailuresAreRetried() {
        catalogue.put("temp", new String[]{"3", "float32"});
        scriptedStatuses.add(503);
        scriptedBody = "busy";

        IngestResult result = client.timeseries().ingestBinary(List.of(collection("temp", 10)),
                BinaryIngestOptions.builder().maxRetries(2).build());

        assertTrue(result.isComplete(), result.toString());
        assertEquals(2, binaryCalls.get());
    }

    @Test
    void aStaleSeriesIsReResolvedAndSentOnceMore() {
        catalogue.put("temp", new String[]{"3", "float32"});
        scriptedStatuses.add(404);
        scriptedBody = "{\"type\":\"https://intellistream.ai/errors/datapoint-block-rejected\",\"reason\":\"unknown-timeseries\",\"timeseriesIds\":[3]}";

        IngestResult result = client.timeseries().ingestBinary(List.of(collection("temp", 10)));

        assertTrue(result.isComplete(), result.toString());
        assertEquals(10, result.succeeded());
        assertEquals(2, binaryCalls.get());
        assertEquals(2, byIdsCalls.get(), "the series was evicted and resolved again");
    }

    @Test
    void theBufferFlushesBySizeAndByAge() throws Exception {
        catalogue.put("temp", new String[]{"3", "float32"});
        List<IngestResult> flushes = new CopyOnWriteArrayList<>();
        try (BinaryIngestBuffer buffer = client.timeseries().binaryBuffer(
                BinaryIngestOptions.builder().zstdLevel(1).build(), 5, Duration.ofMillis(150), flushes::add)) {
            Instant t = Instant.ofEpochMilli(1_700_000_000_000L);
            for (int i = 0; i < 5; i++) {
                buffer.add("temp", t.plusSeconds(i), i * 1.5);
            }
            long deadline = System.currentTimeMillis() + 5_000;
            while (flushes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals(1, flushes.size(), "five points reach maxPoints and flush");
            assertEquals(5, flushes.get(0).succeeded());

            buffer.add("temp", t.plusSeconds(10), 9.0);
            buffer.add("temp", t.plusSeconds(11), 9.5);
            deadline = System.currentTimeMillis() + 5_000;
            while (flushes.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals(2, flushes.size(), "two points age out and flush");
            assertEquals(2, flushes.get(1).succeeded());
            assertEquals(0, buffer.pending());
        }
        assertNotNull(received.get(0).parsed().get(0).valueAsString(0));
        assertEquals(2, binaryCalls.get());
    }
}
