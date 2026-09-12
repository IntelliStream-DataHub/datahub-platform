// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.PayloadCodec;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.controllers.errors.DatapointBlockRejectedException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetAccessDeniedException;
import ai.intellistream.datahub.repositories.node.SeriesMeta;
import ai.intellistream.datahub.tenant.TenantContext;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The binary ingest's contract with the rest of the API: everything is checked before anything is
 * published, the ACL runs once per dataset, quotas are charged after validation, the frames go to
 * Pulsar as they arrived, and the latest-value cache sees each series' last row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatapointBinaryIngestServiceTest {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);
    static final String TENANT = "acme";

    @Mock TimeseriesMetaLookup metaLookup;
    @Mock DataSecurity dataSecurity;
    @Mock IngestQuotaService ingestQuota;
    @Mock LatestDatapointCache latestDatapointCache;
    @Mock Producer<byte[]> producer;
    @Mock TypedMessageBuilder<byte[]> message;
    @Mock LiveIngestCounter counter;

    final LimitsProperties limits = new LimitsProperties();
    final Map<Long, SeriesMeta> catalogue = new HashMap<>();
    DatapointBinaryIngestService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        when(producer.newMessage()).thenReturn(message);
        when(message.property(anyString(), anyString())).thenReturn(message);
        when(message.value(any())).thenReturn(message);
        when(metaLookup.resolve(any())).thenAnswer(inv -> {
            Map<Long, SeriesMeta> found = new HashMap<>();
            for (Long id : inv.<Collection<Long>>getArgument(0)) {
                if (catalogue.containsKey(id)) found.put(id, catalogue.get(id));
            }
            return found;
        });
        service = new DatapointBinaryIngestService(metaLookup, dataSecurity, ingestQuota, latestDatapointCache, producer, counter, limits);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void series(long id, DatapointValueType type, long dataset) {
        catalogue.put(id, new SeriesMeta(id, "s" + id, type.id(), dataset));
    }

    private static byte[] frame(DatapointValueType type, long... ids) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type);
        for (long id : ids) {
            w.series(id, "s" + id);
            for (int p = 0; p < 3; p++) {
                w.add(id, 1_700_000_000_000L + p * 1000L, type.carriesText() ? "v" + p : String.valueOf(id * 10 + p));
            }
        }
        return w.build(ZSTD);
    }

    private static byte[] body(byte[]... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : frames) out.writeBytes(f);
        return out.toByteArray();
    }

    @Test
    void publishesEveryFrameOnlyAfterValidatingAll() throws Exception {
        series(1, DatapointValueType.FLOAT32, 10);
        series(2, DatapointValueType.FLOAT32, 10);
        series(3, DatapointValueType.TEXT, 20);
        byte[] numeric = frame(DatapointValueType.FLOAT32, 1, 2);
        byte[] text = frame(DatapointValueType.TEXT, 3);

        DatapointBinaryIngestService.Summary summary = service.ingest(body(numeric, text), 500);

        assertThat(summary.frames()).isEqualTo(2);
        assertThat(summary.rows()).isEqualTo(9);
        assertThat(summary.series()).isEqualTo(3);

        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(message, times(2)).value(sent.capture());
        assertThat(sent.getAllValues().get(0)).isEqualTo(numeric);
        assertThat(sent.getAllValues().get(1)).isEqualTo(text);
        verify(message, times(2)).send();
        verify(message).property("valueTypeId", "7");
        verify(message).property("valueTypeId", "4");
        verify(message, times(2)).property("compression", "1");

        verify(dataSecurity, times(1)).assertCanWriteDataSet(10L);
        verify(dataSecurity, times(1)).assertCanWriteDataSet(20L);
        verify(ingestQuota).checkAndRecord(IngestQuotaService.QuotaMetric.DATAPOINTS, 9);
        verify(ingestQuota).checkAndRecord(IngestQuotaService.QuotaMetric.TEXT_DATAPOINTS, 3);
        long raw = 0;
        for (DatapointFrame f : DatapointFrame.parseEnvelopes(body(numeric, text))) raw += f.rawLength();
        verify(ingestQuota).checkAndRecord(IngestQuotaService.QuotaMetric.BYTES, raw - 500);

        verify(latestDatapointCache).update("s1", 1_700_000_002_000L, "12.0");
        verify(latestDatapointCache).update("s2", 1_700_000_002_000L, "22.0");
        verify(latestDatapointCache).update("s3", 1_700_000_002_000L, "v2");
        verify(counter).recordIngested(TENANT, 9);
    }

    @Test
    void anUnknownSeriesPublishesNothing() throws Exception {
        series(1, DatapointValueType.FLOAT32, 10);
        byte[] body = frame(DatapointValueType.FLOAT32, 1, 2);

        assertThatThrownBy(() -> service.ingest(body, body.length))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("unknown-timeseries");
                    assertThat(e.getTimeseriesIds()).containsExactly(2L);
                });
        verify(producer, never()).newMessage();
        verify(ingestQuota, never()).checkAndRecord(any(), anyLong());
        verify(latestDatapointCache, never()).update(anyString(), anyLong(), anyString());
    }

    @Test
    void aSeriesOfAnotherValueTypeIsRefused() {
        series(1, DatapointValueType.FLOAT, 10);
        byte[] body = frame(DatapointValueType.FLOAT32, 1);

        assertThatThrownBy(() -> service.ingest(body, body.length))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).isEqualTo("value-type-mismatch");
                    assertThat(e.getFrameIndex()).isZero();
                    assertThat(e.getTimeseriesIds()).containsExactly(1L);
                });
        verify(producer, never()).newMessage();
    }

    @Test
    void aStaleExternalIdIsRefused() {
        catalogue.put(1L, new SeriesMeta(1, "renamed", DatapointValueType.FLOAT32.id(), 10L));
        byte[] body = frame(DatapointValueType.FLOAT32, 1);

        assertThatThrownBy(() -> service.ingest(body, body.length))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).isEqualTo("external-id-mismatch");
                });
        verify(producer, never()).newMessage();
    }

    @Test
    void aDatasetTheCallerCannotWritePublishesNothing() {
        series(1, DatapointValueType.FLOAT32, 10);
        series(2, DatapointValueType.FLOAT32, 20);
        doThrow(new DatasetAccessDeniedException("write", 20L)).when(dataSecurity).assertCanWriteDataSet(20L);
        byte[] body = frame(DatapointValueType.FLOAT32, 1, 2);

        assertThatThrownBy(() -> service.ingest(body, body.length)).isInstanceOf(DatasetAccessDeniedException.class);
        verify(producer, never()).newMessage();
        verify(ingestQuota, never()).checkAndRecord(any(), anyLong());
    }

    @Test
    void aQuotaRefusalPublishesNothing() {
        series(1, DatapointValueType.FLOAT32, 10);
        doThrow(new IllegalStateException("over quota")).when(ingestQuota)
                .checkAndRecord(eq(IngestQuotaService.QuotaMetric.DATAPOINTS), anyLong());
        byte[] body = frame(DatapointValueType.FLOAT32, 1);

        assertThatThrownBy(() -> service.ingest(body, body.length)).hasMessage("over quota");
        verify(producer, never()).newMessage();
    }

    @Test
    void malformedFramesAreRefusedBeforeAnyLookup() {
        byte[] garbage = "not a frame at all, nowhere near one".getBytes();
        assertThatThrownBy(() -> service.ingest(garbage, garbage.length))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("malformed-frame");
                });

        series(1, DatapointValueType.FLOAT32, 10);
        byte[] uncompressed = frame(DatapointValueType.FLOAT32, 1);
        uncompressed[7] = 0;
        assertThatThrownBy(() -> service.ingest(uncompressed, uncompressed.length))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("uncompressed-frame");
                });

        byte[] tooLarge = frame(DatapointValueType.FLOAT32, 1);
        tooLarge[24] = (byte) 0xFF;
        tooLarge[25] = (byte) 0xFF;
        tooLarge[26] = (byte) 0xFF;
        tooLarge[27] = (byte) 0x7F;
        assertThatThrownBy(() -> service.ingest(tooLarge, tooLarge.length))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getReason()).isEqualTo("frame-too-large");
                });
        verify(metaLookup, never()).resolve(any());
    }

    @Test
    void chunkedBodiesAreChargedInFull() throws Exception {
        series(1, DatapointValueType.FLOAT32, 10);
        byte[] body = frame(DatapointValueType.FLOAT32, 1);
        service.ingest(body, -1);
        long raw = DatapointFrame.parseEnvelopes(body).get(0).rawLength();
        verify(ingestQuota).checkAndRecord(IngestQuotaService.QuotaMetric.BYTES, raw);
    }

    @Test
    void tooManyInFlightIsA429WithRetryAfter() {
        limits.setMaxInFlightDatapointsBinary(0);
        DatapointBinaryIngestService saturated = new DatapointBinaryIngestService(
                metaLookup, dataSecurity, ingestQuota, latestDatapointCache, producer, counter, limits);
        byte[] body = frame(DatapointValueType.FLOAT32, 1);

        assertThatThrownBy(() -> saturated.ingest(body, body.length))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getReason()).isEqualTo("too-many-in-flight");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(1);
                });
    }

    @Test
    void thePermitIsReleasedAfterARefusal() {
        limits.setMaxInFlightDatapointsBinary(1);
        DatapointBinaryIngestService single = new DatapointBinaryIngestService(
                metaLookup, dataSecurity, ingestQuota, latestDatapointCache, producer, counter, limits);
        byte[] garbage = new byte[10];
        assertThatThrownBy(() -> single.ingest(garbage, 10)).isInstanceOf(DatapointBlockRejectedException.class);
        series(1, DatapointValueType.FLOAT32, 10);
        byte[] body = frame(DatapointValueType.FLOAT32, 1);
        assertThat(single.ingest(body, body.length).rows()).isEqualTo(3);
    }

    @Test
    void aRequestOfManyFramesDecodesThemAllBeforePublishing() throws Exception {
        for (long id = 1; id <= 6; id++) series(id, DatapointValueType.BIGINT, 10);
        byte[][] frames = new byte[6][];
        for (int i = 0; i < 6; i++) frames[i] = frame(DatapointValueType.BIGINT, i + 1);
        // Break the last frame's payload so the request as a whole must be refused.
        frames[5][frames[5].length - 2] ^= 0x55;

        assertThatThrownBy(() -> service.ingest(body(frames), 0))
                .isInstanceOfSatisfying(DatapointBlockRejectedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo("payload-invalid");
                    assertThat(e.getFrameIndex()).isEqualTo(5);
                });
        verify(producer, never()).newMessage();

        frames[5] = frame(DatapointValueType.BIGINT, 6);
        assertThat(service.ingest(body(frames), 0).frames()).isEqualTo(6);
        verify(message, times(6)).send();
        assertThat(List.of(1, 2, 3)).hasSize(3);
    }
}
