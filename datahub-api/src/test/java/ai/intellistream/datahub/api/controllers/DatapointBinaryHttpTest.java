// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.ApiDatahubApplication;
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
import ai.intellistream.datahub.config.InstanceLock;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /timeseries/data/binary} over real HTTP, through the production filter chain in a real
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
