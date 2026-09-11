// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.services.DatapointBinaryIngestService;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.api.services.LatestDatapointCache;
import ai.intellistream.datahub.api.services.LiveIngestCounter;
import ai.intellistream.datahub.api.services.TimeseriesMetaCache;
import ai.intellistream.datahub.api.services.TimeseriesMetaCache.SeriesMeta;
import ai.intellistream.datahub.tenant.TenantContext;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The binary ingest against a real broker: a frame reaches the block topic byte for byte as the
 * client sent it, with the properties the consumer reads, and a frame at the contract's size cap
 * is accepted by the broker without chunking.
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

    private DatapointBinaryIngestService service(Map<Long, SeriesMeta> catalogue) throws Exception {
        producer = pulsarClient.newProducer(Schema.BYTES)
                .topic(ALL_DATAPOINT_BLOCKS_TOPIC)
                .compressionType(CompressionType.NONE)
                .enableBatching(true)
                .create();
        TimeseriesMetaCache metaCache = mock(TimeseriesMetaCache.class);
        when(metaCache.resolve(anyString(), any())).thenAnswer(inv -> {
            Map<Long, SeriesMeta> found = new HashMap<>();
            for (Long id : inv.<Collection<Long>>getArgument(1)) {
                if (catalogue.containsKey(id)) found.put(id, catalogue.get(id));
            }
            return found;
        });
        TenantContext.setTenantId(TENANT);
        return new DatapointBinaryIngestService(metaCache, mock(DataSecurity.class), mock(IngestQuotaService.class),
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
        Map<Long, SeriesMeta> catalogue = new HashMap<>();
        catalogue.put(1L, new SeriesMeta(1, "a", DatapointValueType.FLOAT32.id(), 10L));
        catalogue.put(2L, new SeriesMeta(2, "b", DatapointValueType.TEXT.id(), 10L));
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

        service.ingest(body.toByteArray(), body.size());

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
        // 100k rows over 10k series with incompressible values: the largest numeric frame the
        // contract allows, and the closest a real frame gets to the broker's 5 MiB message limit.
        Map<Long, SeriesMeta> catalogue = new HashMap<>();
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT);
        Random random = new Random(42);
        for (long id = 1; id <= 10_000; id++) {
            catalogue.put(id, new SeriesMeta(id, "series_" + id, DatapointValueType.FLOAT.id(), 10L));
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

        service.ingest(frame, frame.length);

        Message<byte[]> received = sub.receive(30, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getData()).isEqualTo(frame);
        assertThat(received.getProperty("rows")).isEqualTo("100000");
    }
}
