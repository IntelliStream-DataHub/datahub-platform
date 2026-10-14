// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.ApiDatahubApplication;
import ai.intellistream.datahub.api.binary.ArrowIpc;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon;
import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.init.pulsar.SubscriptionTopicProvisioner;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.api.services.LatestDatapointCache;
import ai.intellistream.datahub.api.services.LiveIngestCounter;
import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.clickhouse.ClickHouseDatapointService;
import ai.intellistream.datahub.config.InstanceLock;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository.IngestTarget;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /timeseries/data/binary} and {@code /timeseries/data/list/binary} over real HTTP, through the production filter chain in a real
 * servlet container: security, the body size filter, the body-cache filter and the request log all
 * sit where they do in production, which is what decides the status a caller actually sees.
 *
 * <p>Requests are written on a raw socket rather than with an HTTP client, because both cases need
 * control a client does not give: a chunked body with no {@code Content-Length}, and a body that
 * stalls halfway so the test can observe what the server does before the body has arrived.
 *
 * <p>The mocks are the start-up network clients, as in {@code SecurityFilterChainTest}. The tenant
 * is put straight into {@link TenantConfigService}'s cache, since its Vault load fails against the
 * {@code ctxtest} profile's dummy address.
 */
@SpringBootTest(
        classes = ApiDatahubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "datahub.limits.max-body-bytes-datapoints-binary=" + DatapointBinaryHttpTest.CAP,
                "datahub.limits.max-in-flight-datapoints-binary=1"
        })
@ActiveProfiles("ctxtest")
class DatapointBinaryHttpTest {

    static final int CAP = 64 * 1024;
    private static final String TOKEN = "tok-binary-http";
    private static final String TENANT = "tenant-binary-http";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TenantConfigService tenantConfigService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private PulsarClient pulsarClient;

    @MockitoBean
    private PulsarAdmin pulsarAdmin;

    @MockitoBean
    private ClickHouseClientPool clickHouseClientPool;

    @MockitoBean
    private SubscriptionTopicProvisioner subscriptionTopicProvisioner;

    @MockitoBean
    private InstanceLock instanceLock;

    @MockitoBean(name = "eventMessageProducer")
    private Producer<?> eventMessageProducer;

    @MockitoBean(name = "subscriptionNotifyProducer")
    private Producer<?> subscriptionNotifyProducer;

    @MockitoBean(name = "allDatapointProducer")
    private Producer<?> allDatapointProducer;

    @MockitoBean(name = "allDatapointBlockProducer")
    private Producer<?> allDatapointBlockProducer;

    @MockitoBean(name = "httpMessageProducer")
    private Producer<?> httpMessageProducer;

    // What the service consults once a body is valid, so a request can get as far as publishing.
    @MockitoBean
    private TimeseriesRepository timeseriesRepository;

    @MockitoBean
    private DataSecurity dataSecurity;

    @MockitoBean
    private IngestQuotaService ingestQuota;

    @MockitoBean
    private LatestDatapointCache latestDatapointCache;

    @MockitoBean(name = "datapointIngestCounter")
    private LiveIngestCounter datapointIngestCounter;

    // The binary read's ClickHouse query, answered with canned Arrow streams.
    @MockitoBean
    private ClickHouseDatapointService clickHouseDatapointService;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("organization", Map.of("org", Map.of("id", TENANT)))
                .claim("realm_access", Map.of("roles", List.of("DATAHUB_ACCESS")))
                .subject("binary-http-test")
                .build());
        tenantConfigService.cachedTenants.put(TENANT, new Tenant());
    }

    @Test
    void aChunkedBodyOverTheCapIs413() throws Exception {
        // No Content-Length, so the filter's pre-check cannot see the size; the cap has to hold as
        // the body is read, and the answer has to be the documented 413.
        try (RawPost post = new RawPost(port)) {
            post.chunk(new byte[CAP / 2]);
            post.chunk(new byte[CAP / 2 + 1]);
            post.finish();
            String response = post.readResponse();
            assertThat(statusOf(response)).as(response).isEqualTo(413);
            assertThat(response).contains("request-too-large");
        }
    }

    @Test
    void theInFlightCapRefusesARequestBeforeItsBodyArrives() throws Exception {
        // One permit, two requests whose bodies stall after the first chunk. The permit has to be
        // taken before the body is read, or the cap bounds nothing about buffered bodies: whichever
        // request gets the permit sits reading, and the other is refused without its body.
        try (RawPost first = new RawPost(port); RawPost second = new RawPost(port)) {
            first.chunk(new byte[1024]);
            second.chunk(new byte[1024]);
            CompletableFuture<String> firstStatus = CompletableFuture.supplyAsync(first::readStatusLine);
            CompletableFuture<String> secondStatus = CompletableFuture.supplyAsync(second::readStatusLine);

            Object refused = CompletableFuture.anyOf(firstStatus, secondStatus).get(20, TimeUnit.SECONDS);

            assertThat((String) refused).startsWith("HTTP/1.1 429");
            assertThat(firstStatus.isDone() && secondStatus.isDone())
                    .as("the request holding the permit is still waiting for its body")
                    .isFalse();
            RawPost holder = firstStatus.isDone() ? second : first;
            holder.finish();
            String holderStatus = (firstStatus.isDone() ? secondStatus : firstStatus).get(20, TimeUnit.SECONDS);
            assertThat(holderStatus).as("a body of zeros is not a frame").startsWith("HTTP/1.1 400");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPublishThatFailsPartwayIsAServerErrorTheClientRetries() throws Exception {
        when(timeseriesRepository.findIngestTargetsByIdIn(anyCollection())).thenReturn(List.of(
                new IngestTarget(1, "a", DatapointValueType.FLOAT32.id(), 10L),
                new IngestTarget(2, "b", DatapointValueType.FLOAT32.id(), 10L)));
        TypedMessageBuilder<byte[]> message = mock(TypedMessageBuilder.class, Answers.RETURNS_SELF);
        when(message.send())
                .thenReturn(mock(MessageId.class))
                .thenThrow(new PulsarClientException("broker went away"));
        doReturn(message).when(allDatapointBlockProducer).newMessage();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(frame(1, "a", 3));
        body.writeBytes(frame(2, "b", 2));

        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/timeseries/data/binary"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", FrameLimits.MEDIA_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // The first frame is on the topic and the second is not. Anything but a status the SDK
        // retries would leave the second frame's rows lost while the caller counts them as stored.
        // A dropped publish is messaging-unavailable, so this is a 503 rather than the 500 written
        // here when the binary path was branched: the SDK retries anything >= 500, so both hold.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        // The retry sends both frames again, so only the one that landed may be counted now.
        verify(ingestQuota).check(IngestQuotaService.QuotaMetric.DATAPOINTS, 5);
        verify(ingestQuota).record(IngestQuotaService.QuotaMetric.DATAPOINTS, 3);
        verify(ingestQuota, never()).record(IngestQuotaService.QuotaMetric.DATAPOINTS, 2);
        verify(latestDatapointCache).update(eq("a"), anyLong(), anyString());
        verify(latestDatapointCache, never()).update(eq("b"), anyLong(), anyString());
    }

    @Test
    void aBinaryReadStreamsFramesInRequestOrder() throws Exception {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        TimeseriesEntity a = series(1, "a");
        TimeseriesEntity b = series(2, "b");
        TimeseriesEntity c = series(3, "c");
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(a, b, c));
        when(clickHouseDatapointService.openArrowStream(any(), seriesId(1), any(), anyLong(), anyInt()))
                .thenAnswer(i -> new ByteArrayInputStream(arrowStream(1, "a", 3)));
        // b has no points in the window: ClickHouse answers with a schema and no batch.
        when(clickHouseDatapointService.openArrowStream(any(), seriesId(2), any(), anyLong(), anyInt()))
                .thenAnswer(i -> new ByteArrayInputStream(ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), List.of())));
        when(clickHouseDatapointService.openArrowStream(any(), seriesId(3), any(), anyLong(), anyInt()))
                .thenAnswer(i -> new ByteArrayInputStream(arrowStream(3, "c", 2)));

        HttpResponse<byte[]> response = read("""
                {"items": [{"start": 1700000000000, "externalId": "c", "limit": null}, {"start": 1700000000000, "externalId": "b"}, {"start": 1700000000000, "id": 1, "limit": 500}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(FrameLimits.MEDIA_TYPE);
        List<DatapointFrame> frames = DatapointFrame.parseResponse(response.body(), new ZstdPayloadCodec());
        assertThat(Arrays.copyOfRange(response.body(), response.body().length - 4, response.body().length))
                .isEqualTo(FrameLimits.END_OF_RESPONSE);
        assertThat(frames).extracting(f -> f.externalIds()[0]).containsExactly("c", "a");
        assertThat(frames).extracting(DatapointFrame::rowCount).containsExactly(2, 3);
        assertThat(frames.get(1).valueAsString(2)).isEqualTo("2.0");
        // An explicit limit is kept; an item without one, sent as null the way the other SDKs do
        // or left out, reads its whole window.
        verify(clickHouseDatapointService).openArrowStream(any(), seriesId(1), any(), eq(500L),
                eq(FrameLimits.maxRows(DatapointValueType.FLOAT32)));
        verify(clickHouseDatapointService).openArrowStream(any(), seriesId(3), any(), eq(0L), anyInt());
    }

    @Test
    void recordBatchesArePackedIntoFramesUnderTheCap() throws Exception {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        TimeseriesEntity text = series(6, "t");
        text.setValueType(new TimeseriesValueType(DatapointValueType.TEXT.id()));
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(text));
        // Three 4 000-row batches against a 10 000-row text frame: two to a frame, the third alone.
        List<ArrowIpc.BatchData> batches = new java.util.ArrayList<>();
        for (int b = 0; b < 3; b++) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(6, "t");
            for (int r = 0; r < 4000; r++) {
                w.addText(6, 1_700_000_000_000L + b * 4000L + r, "v" + r);
            }
            batches.add(DatapointFrame.parseAll(w.build(new ZstdPayloadCodec(1)), new ZstdPayloadCodec(1))
                    .get(0).batches().get(0).toData());
        }
        when(clickHouseDatapointService.openArrowStream(any(), any(), any(), anyLong(), anyInt()))
                .thenAnswer(i -> new ByteArrayInputStream(ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.TEXT), batches)));

        HttpResponse<byte[]> response = read("""
                {"items": [{"start": 1700000000000, "externalId": "t"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        List<DatapointFrame> frames = DatapointFrame.parseResponse(response.body(), new ZstdPayloadCodec());
        assertThat(frames).extracting(DatapointFrame::rowCount).containsExactly(8000, 4000);
        assertThat(frames.get(0).valueAsString(7999)).isEqualTo("v3999");
        assertThat(frames.get(1).timestamp(3999)).isEqualTo(1_700_000_000_000L + 11_999);
        assertThat(frames.get(1).valueAsString(0)).isEqualTo("v0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReadsAnswerIsRelayedAsCompressedAndPostsBackAsItIs() throws Exception {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(series(1, "a")));
        // ClickHouse's answer with its buffers compressed in-band, as the query asks for: the
        // writer's payload is that stream.
        byte[] built = frame(1, "a", 4);
        ByteBuffer header = ByteBuffer.wrap(built).order(ByteOrder.LITTLE_ENDIAN);
        int payloadAt = FrameLimits.HEADER_BYTES + header.getInt(16);
        byte[] compressed = Arrays.copyOfRange(built, payloadAt, payloadAt + header.getInt(20));
        when(clickHouseDatapointService.openArrowStream(any(), seriesId(1), any(), anyLong(), anyInt()))
                .thenAnswer(i -> new ByteArrayInputStream(compressed));

        HttpResponse<byte[]> response = read("""
                {"items": [{"start": 1700000000000, "externalId": "a"}]}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()[7]).as("the frame's compression byte").isEqualTo((byte) FrameLimits.COMPRESSION_ARROW_ZSTD);
        assertThat(DatapointFrame.parseResponse(response.body(), new ZstdPayloadCodec()).get(0).valueAsString(3)).isEqualTo("3.0");

        // The body as answered, end marker and all, is an insert.
        when(timeseriesRepository.findIngestTargetsByIdIn(anyCollection())).thenReturn(List.of(
                new IngestTarget(1, "a", DatapointValueType.FLOAT32.id(), 10L)));
        TypedMessageBuilder<byte[]> message = mock(TypedMessageBuilder.class, Answers.RETURNS_SELF);
        when(message.send()).thenReturn(mock(MessageId.class));
        doReturn(message).when(allDatapointBlockProducer).newMessage();
        HttpResponse<String> posted = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/timeseries/data/binary"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", FrameLimits.MEDIA_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(response.body()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(204);
        verify(message).property("compression", "2");
        verify(ingestQuota).record(IngestQuotaService.QuotaMetric.DATAPOINTS, 4);
    }

    @Test
    void aBinaryReadIsRefusedWithAProblemBeforeAnyFrame() throws Exception {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(series(1, "a")));

        HttpResponse<byte[]> cursor = read("""
                {"items": [{"start": 1700000000000, "externalId": "a", "cursor": "abc"}]}
                """);
        assertThat(cursor.statusCode()).isEqualTo(400);
        assertThat(cursor.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(new String(cursor.body(), StandardCharsets.UTF_8)).contains("start");

        HttpResponse<byte[]> overBudget = read("""
                {"items": [{"start": 1700000000000, "externalId": "a", "limit": 100000}], "limit": 10}
                """);
        assertThat(overBudget.statusCode()).isEqualTo(400);

        HttpResponse<byte[]> unknown = read("""
                {"items": [{"start": 1700000000000, "externalId": "a"}, {"start": 1700000000000, "externalId": "nope"}]}
                """);
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(new String(unknown.body(), StandardCharsets.UTF_8)).contains("nope");
        verify(clickHouseDatapointService, never()).openArrowStream(any(), any(), any(), anyLong(), anyInt());

        when(clickHouseDatapointService.openArrowStream(any(), any(), any(), anyLong(), anyInt()))
                .thenAnswer(i -> new ByteArrayInputStream(ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), List.of())));
        HttpResponse<byte[]> ignored = read("""
                {"items": [{"start": 1700000000000, "externalId": "a"}, {"start": 1700000000000, "externalId": "nope"}], "ignoreUnknownIds": true}
                """);
        assertThat(ignored.statusCode()).isEqualTo(200);
        assertThat(ignored.body()).isEqualTo(FrameLimits.END_OF_RESPONSE);
    }

    @Test
    void aClickHouseFailureBeforeTheFirstFrameIsAServerErrorNotAnEmptyAnswer() throws Exception {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(series(1, "a")));
        when(clickHouseDatapointService.openArrowStream(any(), any(), any(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("ClickHouse went away"));

        HttpResponse<byte[]> response = read("""
                {"items": [{"start": 1700000000000, "externalId": "a"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
    }

    @Test
    void aFailureAfterTheFirstFrameAbortsTheResponseRatherThanEndingIt() throws Exception {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(series(1, "a"), series(2, "b")));
        when(clickHouseDatapointService.openArrowStream(any(), seriesId(1), any(), anyLong(), anyInt()))
                .thenAnswer(i -> new ByteArrayInputStream(arrowStream(1, "a", 3)));
        when(clickHouseDatapointService.openArrowStream(any(), seriesId(2), any(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("ClickHouse went away"));

        // The first frame was flushed, so the 200 and the frame are out. The container then drops
        // the connection without the last chunk: a client sees a transport error, and one that kept
        // what arrived holds a body without the end marker. Either way, never a complete answer.
        assertThatThrownBy(() -> read("""
                {"items": [{"start": 1700000000000, "externalId": "a"}, {"start": 1700000000000, "externalId": "b"}]}
                """)).isInstanceOf(IOException.class);
        verify(clickHouseDatapointService).openArrowStream(any(), seriesId(2), any(), anyLong(), anyInt());
    }

    @Test
    void textSeriesHaveNoAggregates() throws Exception {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        TimeseriesEntity text = series(5, "t");
        text.setValueType(new TimeseriesValueType(DatapointValueType.TEXT.id()));
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(text));

        HttpResponse<byte[]> response = read("""
                {"items": [{"start": 1700000000000, "externalId": "t", "aggregates": ["avg"], "granularity": "1h"}]}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("text");
    }

    private HttpResponse<byte[]> read(String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/timeseries/data/list/binary"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .header("Accept", FrameLimits.MEDIA_TYPE + ", application/problem+json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static TimeseriesEntity seriesId(long id) {
        return argThat(s -> s != null && s.getId() == id);
    }

    private static TimeseriesEntity series(long id, String externalId) {
        TimeseriesEntity ts = new TimeseriesEntity();
        ts.setId(id);
        ts.setExternalId(externalId);
        ts.setValueType(new TimeseriesValueType(DatapointValueType.FLOAT32.id()));
        return ts;
    }

    /** What ClickHouse answers for one series: the canon's Arrow stream. */
    private static byte[] arrowStream(long id, String externalId, int rows) {
        DatapointFrame f = DatapointFrame.parseAll(frame(id, externalId, rows), new ZstdPayloadCodec(1)).get(0);
        return ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32),
                f.batches().stream().map(ArrowIpc.Batch::toData).toList());
    }

    private static byte[] frame(long id, String externalId, int rows) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(id, externalId);
        for (int r = 0; r < rows; r++) {
            w.addFloat32(id, 1_700_000_000_000L + r * 1000L, r);
        }
        return w.build(new ZstdPayloadCodec(1));
    }

    private static int statusOf(String response) {
        return Integer.parseInt(response.substring(9, 12));
    }

    /** A chunked POST on a raw socket, written piece by piece. */
    private static final class RawPost implements AutoCloseable {

        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;

        RawPost(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(30_000);
            out = socket.getOutputStream();
            in = socket.getInputStream();
            write("POST /timeseries/data/binary HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Authorization: Bearer " + TOKEN + "\r\n"
                    + "Content-Type: " + FrameLimits.MEDIA_TYPE + "\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
        }

        void chunk(byte[] data) throws IOException {
            write(Integer.toHexString(data.length) + "\r\n");
            out.write(data);
            write("\r\n");
        }

        void finish() throws IOException {
            write("0\r\n\r\n");
        }

        private void write(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        String readStatusLine() {
            try {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1 && b != '\n') {
                    line.write(b);
                }
                return line.toString(StandardCharsets.US_ASCII).trim();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Everything until the server closes the connection, which {@code Connection: close} asks for. */
        String readResponse() throws IOException {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
