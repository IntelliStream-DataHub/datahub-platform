// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.ingest.DatapointSpool;
import ai.intellistream.datahub.sdk.ingest.IngestOptions;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import ai.intellistream.datahub.sdk.timeseries.Datapoint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableBufferTest {

    @Test
    void spoolsWhenServerDownThenFlushesOnRecovery(@TempDir java.nio.file.Path dir) throws Exception {
        AtomicInteger status = new AtomicInteger(503); // "server down" to start
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/data", exchange -> {
            int code = status.get();
            byte[] body = (code == 200 ? "{\"items\":[\"ok\"]}" : "down").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .bufferRetention(Duration.ofHours(1))
                    .bufferDirectory(dir)
                    .build());
            IngestOptions noRetry = IngestOptions.builder().maxRetries(0).build();

            // Server down: the 3 datapoints fail and get spooled.
            IngestResult down = client.timeseries().ingest(Map.of("mem_used", List.of(
                    Datapoint.of(Instant.now(), 1L),
                    Datapoint.of(Instant.now(), 2L),
                    Datapoint.of(Instant.now(), 3L))), noRetry);
            assertFalse(down.isComplete());
            assertEquals(3, down.buffered());

            // Server back: the next call drains the 3 buffered, then sends 2 new -> nothing buffered.
            status.set(200);
            IngestResult up = client.timeseries().ingest(Map.of("mem_used", List.of(
                    Datapoint.of(Instant.now(), 4L),
                    Datapoint.of(Instant.now(), 5L))), noRetry);
            assertTrue(up.isComplete());
            assertEquals(0, up.buffered());

            // A further call has an empty spool and ingests live.
            IngestResult live = client.timeseries().ingest(
                    Map.of("mem_used", List.of(Datapoint.of(Instant.now(), 6L))), noRetry);
            assertTrue(live.isComplete());
            assertEquals(1, live.succeeded());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void spoolsOnAuthFailureThenFlushesOnceCredentialRestored(@TempDir java.nio.file.Path dir) throws Exception {
        // 401 (expired/invalid token) and 403 (forbidden) are recoverable: datapoints spool while the
        // credential is fixed out-of-band, then flush — rather than being surfaced/dropped as terminal.
        for (int authStatus : new int[]{401, 403}) {
            java.nio.file.Path spoolDir = dir.resolve("auth-" + authStatus);
            AtomicInteger status = new AtomicInteger(authStatus);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/timeseries/data", exchange -> {
                int code = status.get();
                byte[] body = (code == 200 ? "{\"items\":[\"ok\"]}" : "denied").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            try {
                DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .token("test-token")
                        .bufferRetention(Duration.ofHours(1))
                        .bufferDirectory(spoolDir)
                        .build());
                IngestOptions noRetry = IngestOptions.builder().maxRetries(0).build();

                // Auth fails: the 2 datapoints are spooled, not surfaced as a terminal error.
                IngestResult denied = client.timeseries().ingest(Map.of("mem_used", List.of(
                        Datapoint.of(Instant.now(), 1L),
                        Datapoint.of(Instant.now(), 2L))), noRetry);
                assertFalse(denied.isComplete());
                assertEquals(2, denied.buffered(), "status " + authStatus + " should spool, not drop");

                // Credential restored: the next call drains the backlog and the new point lands.
                status.set(200);
                IngestResult ok = client.timeseries().ingest(
                        Map.of("mem_used", List.of(Datapoint.of(Instant.now(), 3L))), noRetry);
                assertTrue(ok.isComplete(), "status " + authStatus + " backlog should flush on recovery");
                assertEquals(0, ok.buffered());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void terminalErrorIsSurfacedNotBuffered(@TempDir java.nio.file.Path dir) throws Exception {
        // A 400 is the caller's fault (bad payload); buffering it would loop forever, so it surfaces.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/data", exchange -> {
            byte[] body = "bad request".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .bufferRetention(Duration.ofHours(1))
                    .bufferDirectory(dir)
                    .build());
            IngestOptions noRetry = IngestOptions.builder().maxRetries(0).build();

            IngestResult result = client.timeseries().ingest(
                    Map.of("mem_used", List.of(Datapoint.of(Instant.now(), 1L))), noRetry);
            assertFalse(result.isComplete());
            assertEquals(0, result.buffered(), "a 400 must not be buffered");
            assertEquals(1, result.failed());
            assertEquals(400, result.errors().get(0).statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appendDrainRoundTripsAllDatapoints(@TempDir java.nio.file.Path dir) {
        JsonMapper mapper = JsonMapper.builder().build();
        DatapointSpool spool = new DatapointSpool(dir, Duration.ofHours(1), null, mapper);
        long now = System.currentTimeMillis();

        spool.append(List.of(collection("mem_used", now, "1", "2")), now);
        spool.append(List.of(collection("mem_free", now, "3")), now);
        assertEquals(3, spool.size());

        List<String> sent = new ArrayList<>();
        boolean drained = spool.drain(chunk -> {
            for (DatapointsCollection c : chunk) {
                for (DatapointString d : c.getDatapoints()) {
                    sent.add(d.getValue());
                }
            }
            return true; // accept everything
        }, now);

        assertTrue(drained);
        assertEquals(0, spool.size());
        assertEquals(List.of("1", "2", "3"), sent.stream().sorted().toList());
    }

    @Test
    void retentionDropsDatapointsOlderThanWindow(@TempDir java.nio.file.Path dir) {
        JsonMapper mapper = JsonMapper.builder().build();
        DatapointSpool spool = new DatapointSpool(dir, Duration.ofMinutes(60), null, mapper);

        long now = System.currentTimeMillis();
        DatapointsCollection c = new DatapointsCollection();
        c.setExternalId("mem_used");
        c.setDatapoints(List.of(
                new DatapointString(Long.toString(now - Duration.ofHours(2).toMillis()), "old"),
                new DatapointString(Long.toString(now), "fresh")));
        spool.append(List.of(c), now);

        List<String> sent = new ArrayList<>();
        spool.drain(chunk -> {
            chunk.forEach(col -> col.getDatapoints().forEach(d -> sent.add(d.getValue())));
            return true;
        }, now);

        assertEquals(List.of("fresh"), sent); // the 2-hour-old point was skipped
    }

    @Test
    void recoversSpooledDataAcrossClientRestart(@TempDir java.nio.file.Path dir) {
        JsonMapper mapper = JsonMapper.builder().build();
        long now = System.currentTimeMillis();

        DatapointSpool first = new DatapointSpool(dir, Duration.ofHours(1), null, mapper);
        first.append(List.of(collection("mem_used", now, "1", "2")), now);
        assertEquals(2, first.size());

        // A fresh spool over the same directory should find the on-disk segment.
        DatapointSpool restarted = new DatapointSpool(dir, Duration.ofHours(1), null, mapper);
        assertEquals(2, restarted.size());
    }

    @Test
    void sealsAtRolloverAndDrainsCompressedSegments(@TempDir java.nio.file.Path dir) throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        // 4 MiB cap -> ~1 MiB rollover; the data (~2 MiB) crosses it so a segment seals, and the
        // compressed total stays far under the cap so nothing is evicted.
        DatapointSpool spool = new DatapointSpool(dir, null, 4L * 1024 * 1024, mapper);
        long now = System.currentTimeMillis();

        int batches = 30;
        int perBatch = 1_000;
        for (int b = 0; b < batches; b++) {
            DatapointsCollection c = new DatapointsCollection();
            c.setExternalId("mem_used");
            List<DatapointString> pts = new ArrayList<>(perBatch);
            for (int i = 0; i < perBatch; i++) {
                pts.add(new DatapointString(Long.toString(now), Integer.toString(b * perBatch + i)));
            }
            c.setDatapoints(pts);
            spool.append(List.of(c), now);
        }
        int total = batches * perBatch;
        assertEquals(total, spool.size());

        long sealed;
        try (var files = java.nio.file.Files.list(dir)) {
            sealed = files.filter(p -> p.getFileName().toString().endsWith(".ndjson.gz")).count();
        }
        assertTrue(sealed >= 1, "expected at least one gzip-sealed segment, found " + sealed);

        AtomicInteger drained = new AtomicInteger();
        boolean ok = spool.drain(chunk -> {
            chunk.forEach(c -> drained.addAndGet(c.getDatapoints().size()));
            return true;
        }, now);

        assertTrue(ok);
        assertEquals(total, drained.get()); // every record survived seal + compressed read-back
        assertEquals(0, spool.size());
    }

    @Test
    void bufferingIsOffByDefaultAndOptInAppliesDefaults() {
        DatahubConfig off = DatahubConfig.builder().baseUrl("http://x").token("t").build();
        assertFalse(off.hasBuffering());

        DatahubConfig on = DatahubConfig.builder().baseUrl("http://x").token("t").enableBuffering().build();
        assertTrue(on.hasBuffering());
        assertEquals(DatahubConfig.DEFAULT_BUFFER_RETENTION, on.bufferRetention());
        assertEquals(DatahubConfig.DEFAULT_BUFFER_MAX_BYTES, on.bufferMaxBytes());

        // Setting one dimension opts in and defaults the other.
        DatahubConfig timeOnly = DatahubConfig.builder().baseUrl("http://x").token("t")
                .bufferRetention(Duration.ofMinutes(30)).build();
        assertTrue(timeOnly.hasBuffering());
        assertEquals(Duration.ofMinutes(30), timeOnly.bufferRetention());
        assertEquals(DatahubConfig.DEFAULT_BUFFER_MAX_BYTES, timeOnly.bufferMaxBytes());
    }

    private static DatapointsCollection collection(String externalId, long ts, String... values) {
        DatapointsCollection c = new DatapointsCollection();
        c.setExternalId(externalId);
        List<DatapointString> points = new ArrayList<>();
        for (String v : values) {
            points.add(new DatapointString(Long.toString(ts), v));
        }
        c.setDatapoints(points);
        return c;
    }
}
