// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.controllers.errors.DatapointBlockRejectedException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.services.DatapointBinaryIngestService;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.api.services.LatestDatapointCache;
import ai.intellistream.datahub.api.services.LiveIngestCounter;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository.IngestTarget;
import ai.intellistream.datahub.tenant.TenantContext;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The binary ingest against a real broker: a frame reaches the block topic byte for byte as the
 * client sent it, with the properties the consumer reads, a frame at the contract's size cap is
 * accepted by the broker without chunking, and one over it is refused before anything is sent.
 */
class DatapointBinaryIngestIT extends AbstractPulsarWebSocketIT {

    static final String TENANT = "it-tenant";

    private Producer<byte[]> producer;
    private Consumer<byte[]> consumer;

    @AfterEach
    void tearDown() throws Exception {
        TenantContext.clear();
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
    }

    private DatapointBinaryIngestService service(Map<Long, IngestTarget> catalogue) throws Exception {
        producer = pulsarClient.newProducer(Schema.BYTES)
                .topic(ALL_DATAPOINT_BLOCKS_TOPIC)
                .compressionType(CompressionType.NONE)
                .enableBatching(true)
                .create();
        TimeseriesRepository timeseriesRepository = mock(TimeseriesRepository.class);
        when(timeseriesRepository.findIngestTargetsByIdIn(anyCollection())).thenAnswer(inv -> {
            List<IngestTarget> found = new ArrayList<>();
            for (Long id : inv.<Collection<Long>>getArgument(0)) {
                if (catalogue.containsKey(id)) found.add(catalogue.get(id));
            }
            return found;
        });
        TenantContext.setTenantId(TENANT);
        return new DatapointBinaryIngestService(timeseriesRepository, mock(DataSecurity.class), mock(IngestQuotaService.class),
                mock(LatestDatapointCache.class), producer, mock(LiveIngestCounter.class), new LimitsProperties());
    }

    private Consumer<byte[]> subscribe(String name) throws Exception {
        consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(ALL_DATAPOINT_BLOCKS_TOPIC)
                .subscriptionName(name)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Latest)
                .subscribe();
        return consumer;
    }

    @Test
    void framesReachTheTopicAsSent() throws Exception {
        Map<Long, IngestTarget> catalogue = new HashMap<>();
        catalogue.put(1L, new IngestTarget(1, "a", DatapointValueType.FLOAT32.id(), 10L));
        catalogue.put(2L, new IngestTarget(2, "b", DatapointValueType.TEXT.id(), 10L));
        Consumer<byte[]> sub = subscribe("it-blocks-" + System.nanoTime());
        DatapointBinaryIngestService service = service(catalogue);

        DatapointFrameWriter f1 = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(1, "a");
        f1.addFloat32(1, 1000, 1.5f);
        DatapointFrameWriter f2 = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(2, "b");
        f2.addText(2, 1000, "on");
        byte[] first = f1.build(new ZstdPayloadCodec(9));
        byte[] second = f2.build(new ZstdPayloadCodec(9));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(first);
        body.writeBytes(second);

        service.ingest(new ByteArrayInputStream(body.toByteArray()), body.size());

        Message<byte[]> m1 = sub.receive(20, TimeUnit.SECONDS);
        Message<byte[]> m2 = sub.receive(20, TimeUnit.SECONDS);
        assertThat(m1).isNotNull();
        assertThat(m2).isNotNull();
        assertThat(m1.getData()).isEqualTo(first);
        assertThat(m2.getData()).isEqualTo(second);
        assertThat(m1.getProperty("tenantId")).isEqualTo(TENANT);
        assertThat(m1.getProperty("valueTypeId")).isEqualTo("7");
        assertThat(m2.getProperty("valueTypeId")).isEqualTo("4");
        assertThat(m1.getProperty("compression")).isEqualTo("1");
        // What arrived is still a valid, still compressed frame.
        DatapointFrame frame = DatapointFrame.parseAll(m1.getData(), new ZstdPayloadCodec()).get(0);
        assertThat(frame.valueAsString(0)).isEqualTo("1.5");
    }

    @Test
    void aFrameAtTheSizeCapPassesTheBroker() throws Exception {
        // 100k rows over 10k series with incompressible values: the largest numeric payload the
        // contract allows. The directory is what can take a frame further; see the next test.
        Map<Long, IngestTarget> catalogue = new HashMap<>();
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT);
        Random random = new Random(42);
        for (long id = 1; id <= 10_000; id++) {
            catalogue.put(id, new IngestTarget(id, "series_" + id, DatapointValueType.FLOAT.id(), 10L));
            w.series(id, "series_" + id);
            long ts = 1_700_000_000_000L;
            for (int p = 0; p < 10; p++) {
                ts += 1 + random.nextInt(100_000);
                w.addFloat(id, ts, random.nextDouble());
            }
        }
        byte[] frame = w.build(new ZstdPayloadCodec(1));
        assertThat(frame.length).isGreaterThan(1024 * 1024);
        Consumer<byte[]> sub = subscribe("it-big-" + System.nanoTime());
        DatapointBinaryIngestService service = service(catalogue);

        service.ingest(new ByteArrayInputStream(frame), frame.length);

        Message<byte[]> received = sub.receive(30, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getData()).isEqualTo(frame);
        assertThat(received.getProperty("rows")).isEqualTo("100000");
    }

    @Test
    void aFrameOfExactlyTheCapAsSentPassesTheBroker() throws Exception {
        // The cap has to leave room for what a message carries besides the frame, with batching on
        // as the api's producer has it. A real frame's directory is padded until the frame is
        // exactly the cap.
        int series = 10_000;
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT);
        Random random = new Random(3);
        for (long id = 1; id <= series; id++) {
            w.series(id, "s" + id);
            w.addFloat(id, 1_700_000_000_000L, random.nextDouble());
        }
        byte[] base = w.build(new ZstdPayloadCodec(1));
        int payloadLength = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN).getInt(20);
        // Each entry is an 8-byte id and a 2-byte length before its name; the names share the rest.
        long nameBytes = FrameLimits.MAX_FRAME_BYTES - FrameLimits.HEADER_BYTES - payloadLength - series * 10L;
        long each = nameBytes / series;
        long extra = nameBytes % series;
        assertThat(each).isBetween(128L, (long) FrameLimits.MAX_EXTERNAL_ID_BYTES - 1);
        Map<Long, IngestTarget> catalogue = new HashMap<>();
        Map<Long, String> padded = new HashMap<>();
        for (long id = 1; id <= series; id++) {
            int length = (int) (each + (id <= extra ? 1 : 0));
            // Two-byte characters, so the names stay inside 256 characters.
            String name = "ø".repeat(length / 2) + "x".repeat(length % 2);
            padded.put(id, name);
            catalogue.put(id, new IngestTarget(id, name, DatapointValueType.FLOAT.id(), 10L));
        }
        byte[] frame = withExternalIds(base, padded);
        assertThat(frame.length).isEqualTo(FrameLimits.MAX_FRAME_BYTES);
        Consumer<byte[]> sub = subscribe("it-at-cap-" + System.nanoTime());
        DatapointBinaryIngestService service = service(catalogue);

        service.ingest(new ByteArrayInputStream(frame), frame.length);

        Message<byte[]> received = sub.receive(30, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getData()).isEqualTo(frame);
    }

    @Test
    void aFrameLargerThanTheBrokerTakesIsRefusedBeforeAnythingIsPublished() throws Exception {
        // Every number here is inside a per-field cap: 10,000 series, 100,000 rows, and external
        // ids of 256 characters. With a two-byte character in most positions the directory is
        // 5.2 MB, and with the payload the frame is well past the broker's 5 MiB message limit.
        Map<Long, IngestTarget> catalogue = new HashMap<>();
        DatapointFrameWriter small = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(1, "first");
        small.addFloat32(1, 1000, 1f);
        catalogue.put(1L, new IngestTarget(1, "first", DatapointValueType.FLOAT32.id(), 10L));
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT);
        Map<Long, String> longNames = new HashMap<>();
        Random random = new Random(7);
        for (long id = 2; id <= 10_001; id++) {
            String name = (id + "-" + "ø".repeat(256)).substring(0, 256);
            longNames.put(id, name);
            catalogue.put(id, new IngestTarget(id, name, DatapointValueType.FLOAT.id(), 10L));
            w.series(id, "s" + id);
            for (int p = 0; p < 10; p++) {
                w.addFloat(id, 1_700_000_000_000L + p * 1000L, random.nextDouble());
            }
        }
        byte[] oversized = withExternalIds(w.build(new ZstdPayloadCodec(1)), longNames);
        assertThat(oversized.length).isGreaterThan(5 * 1024 * 1024);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(small.build(new ZstdPayloadCodec(1)));
        body.writeBytes(oversized);
        Consumer<byte[]> sub = subscribe("it-oversized-" + System.nanoTime());
        DatapointBinaryIngestService service = service(catalogue);

        assertThatThrownBy(() -> service.ingest(new ByteArrayInputStream(body.toByteArray()), body.size()))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getReason()).isEqualTo("frame-too-large");
                    assertThat(e.getFrameIndex()).isEqualTo(1);
                });
        assertThat(sub.receive(3, TimeUnit.SECONDS)).as("not even the frame that fits").isNull();
    }

    /**
     * The same frame with its directory rewritten to other external ids. The writer refuses to build
     * a frame over the cap, so a frame that is has to be put together by hand, as another producer
     * could.
     */
    static byte[] withExternalIds(byte[] frame, Map<Long, String> names) {
        ByteBuffer in = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        int seriesCount = in.getInt(12);
        int directoryLength = in.getInt(16);
        int payloadLength = in.getInt(20);
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        int pos = FrameLimits.HEADER_BYTES;
        for (int s = 0; s < seriesCount; s++) {
            long id = in.getLong(pos);
            pos += 8;
            int len = 0;
            int shift = 0;
            int b;
            do {
                b = frame[pos++] & 0xFF;
                len |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            pos += len;
            byte[] name = names.get(id).getBytes(StandardCharsets.UTF_8);
            ByteBuffer entry = ByteBuffer.allocate(8 + 5 + name.length).order(ByteOrder.LITTLE_ENDIAN);
            entry.putLong(id);
            int v = name.length;
            while ((v & ~0x7F) != 0) {
                entry.put((byte) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            entry.put((byte) v);
            entry.put(name);
            directory.write(entry.array(), 0, entry.position());
        }
        ByteBuffer out = ByteBuffer.allocate(FrameLimits.HEADER_BYTES + directory.size() + payloadLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(frame, 0, FrameLimits.HEADER_BYTES);
        out.putInt(16, directory.size());
        out.put(directory.toByteArray());
        out.put(frame, FrameLimits.HEADER_BYTES + directoryLength, payloadLength);
        return out.array();
    }
}
