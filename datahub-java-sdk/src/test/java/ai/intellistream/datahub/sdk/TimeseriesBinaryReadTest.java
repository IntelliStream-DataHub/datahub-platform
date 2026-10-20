// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code retrieveBinary} against a stand-in server: the request is the JSON retrieve body with the
 * frame media type asked for, the frames come back decoded, whether compressed whole or in-band
 * as the server relays them, a refusal or a response cut short surfaces as a
 * {@link DatahubApiException} rather than as fewer points, and {@code ingestFrames} posts them
 * back as they are.
 */
class TimeseriesBinaryReadTest {

    final JsonMapper json = JsonMapper.builder().build();
    volatile int status = 200;
    volatile byte[] answer;
    volatile JsonNode lastRequest;
    volatile String lastAccept;
    final List<byte[]> posted = new CopyOnWriteArrayList<>();
    HttpServer server;
    DatahubClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/data/list/binary", exchange -> {
            lastAccept = exchange.getRequestHeaders().getFirst("Accept");
            lastRequest = json.readTree(exchange.getRequestBody().readAllBytes());
            reply(exchange, status, status == 200 ? FrameLimits.MEDIA_TYPE : "application/problem+json", answer);
        });
        server.createContext("/timeseries/data/binary", exchange -> {
            posted.add(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
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

    private static void reply(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** A frame as the writer builds it and as the server relays it: the batches compressed in-band. */
    private static byte[] frame(long id, String externalId, int rows) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(id, externalId);
        for (int r = 0; r < rows; r++) {
            w.addFloat(id, 1_700_000_000_000L + r * 1000L, r * 1.5);
        }
        return w.build(new ZstdPayloadCodec(1));
    }

    private static byte[] response(byte[]... frames) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] f : frames) body.writeBytes(f);
        body.writeBytes(FrameLimits.END_OF_RESPONSE);
        return body.toByteArray();
    }

    private static DataRetriever<RetrieveFilter> request(String... externalIds) {
        DataRetriever<RetrieveFilter> request = new DataRetriever<>();
        for (String externalId : externalIds) {
            RetrieveFilter f = new RetrieveFilter();
            f.setExternalId(externalId);
            f.setStart(Instant.ofEpochMilli(1_700_000_000_000L).atZone(ZoneOffset.UTC));
            f.setLimit(1000);
            request.getItems().add(f);
        }
        return request;
    }

    @Test
    void framesComeBackDecodedInOrder() {
        answer = response(frame(2, "b", 3), frame(1, "a", 2));

        List<DatapointFrame> frames = client.timeseries().retrieveBinary(request("b", "a"));

        assertEquals(2, frames.size());
        assertEquals("b", frames.get(0).externalIds()[0]);
        assertEquals(3, frames.get(0).rowCount());
        assertEquals("3.0", frames.get(0).valueAsString(2));
        assertEquals(1_700_000_002_000L, frames.get(0).timestamp(2));
        assertEquals("a", frames.get(1).externalIds()[0]);
        assertTrue(lastAccept.startsWith(FrameLimits.MEDIA_TYPE), lastAccept);
        assertTrue(lastAccept.contains("application/problem+json"), lastAccept);
        assertEquals("b", lastRequest.path("items").get(0).path("externalId").asString());
        assertEquals(1000, lastRequest.path("items").get(0).path("limit").asInt());
    }

    @Test
    void relayedFramesComeBackDecodedAndPostBackAsTheyAre() throws Exception {
        byte[] a = frame(1, "a", 3);
        byte[] b = frame(2, "b", 2);
        answer = response(a, b);

        List<DatapointFrame> frames = client.timeseries().retrieveBinary(request("a", "b"));
        assertEquals(2, frames.size());
        assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, frames.get(0).compression());
        assertEquals("3.0", frames.get(0).valueAsString(2));
        assertEquals("1.5", frames.get(1).valueAsString(1));

        client.timeseries().ingestFrames(frames);
        assertEquals(1, posted.size());
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(a);
        expected.writeBytes(b);
        assertArrayEquals(expected.toByteArray(), posted.get(0));
        assertEquals(2, DatapointFrame.parseAll(posted.get(0), new ZstdPayloadCodec()).size());
    }

    @Test
    void framesArePostedInAsManyRequestsAsTheCapsNeed() throws Exception {
        byte[][] many = new byte[FrameLimits.MAX_FRAMES_PER_REQUEST + 1][];
        for (int i = 0; i < many.length; i++) {
            many[i] = frame(1, "a", 1);
        }
        answer = response(many);
        List<DatapointFrame> frames = client.timeseries().retrieveBinary(request("a"));
        assertEquals(many.length, frames.size());

        client.timeseries().ingestFrames(frames);
        assertEquals(2, posted.size());
        assertEquals(FrameLimits.MAX_FRAMES_PER_REQUEST, DatapointFrame.parseAll(posted.get(0), new ZstdPayloadCodec()).size());
        assertEquals(1, DatapointFrame.parseAll(posted.get(1), new ZstdPayloadCodec()).size());
    }

    @Test
    void noPointsIsNoFrames() {
        answer = response();
        assertTrue(client.timeseries().retrieveBinary(request("a")).isEmpty());
    }

    @Test
    void aResponseCutShortIsAnErrorNotFewerPoints() {
        byte[] full = response(frame(1, "a", 2), frame(2, "b", 2));
        // Cut cleanly between the two frames: every byte that arrived parses, but the marker is missing.
        answer = Arrays.copyOf(full, frame(1, "a", 2).length);

        DatahubApiException e = assertThrows(DatahubApiException.class,
                () -> client.timeseries().retrieveBinary(request("a", "b")));
        assertEquals(0, e.statusCode());
        assertTrue(e.getMessage().contains("cut short"), e.getMessage());
    }

    @Test
    void aRefusalIsTheProblemTheServerSent() {
        status = 400;
        answer = """
                {"type":"https://intellistream.ai/errors/bad-request","title":"Bad Request","status":400,
                 "detail":"The binary read has no cursors; page by moving `start` past the last timestamp read."}
                """.getBytes(StandardCharsets.UTF_8);

        DatahubApiException e = assertThrows(DatahubApiException.class,
                () -> client.timeseries().retrieveBinary(request("a")));
        assertEquals(400, e.statusCode());
        assertTrue(e.getMessage().contains("no cursors"), e.getMessage());
    }
}
