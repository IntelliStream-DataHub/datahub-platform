// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrame.Run;
import ai.intellistream.datahub.api.binary.FrameFormatException;
import ai.intellistream.datahub.api.binary.PayloadCodec;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.controllers.errors.DatapointBlockRejectedException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.services.TimeseriesMetaCache.SeriesMeta;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * {@code POST /timeseries/data/binary}: every frame of the body is validated before any is
 * published, then each is forwarded to Pulsar as the client sent it. The frames stay compressed
 * end to end; this class decompresses copies only to check them.
 *
 * <p>Order of work, and why: the envelopes and directories first (cheap, no payload touched),
 * then the payloads in parallel, then one cached lookup per distinct series, the dataset write
 * check once per distinct dataset, the value type and external id of every series against what
 * the frame claims, the quotas, and only then the publish. A failure anywhere means nothing was
 * sent, so the SDK can retry the whole request without double inserts.
 */
@Service
@Slf4j
public class DatapointBinaryIngestService {

    public record Summary(int frames, long rows, int series) {
    }

    private final TimeseriesMetaCache metaCache;
    private final DataSecurity dataSecurity;
    private final IngestQuotaService ingestQuota;
    private final LatestDatapointCache latestDatapointCache;
    private final Producer<byte[]> blockProducer;
    private final LiveIngestCounter datapointIngestCounter;
    private final int inFlightLimit;
    private final Semaphore inFlight;
    private final PayloadCodec codec = new ZstdPayloadCodec();

    public DatapointBinaryIngestService(TimeseriesMetaCache metaCache,
                                        DataSecurity dataSecurity,
                                        IngestQuotaService ingestQuota,
                                        LatestDatapointCache latestDatapointCache,
                                        @Qualifier("allDatapointBlockProducer") Producer<byte[]> blockProducer,
                                        @Qualifier("datapointIngestCounter") LiveIngestCounter datapointIngestCounter,
                                        LimitsProperties limits) {
        this.metaCache = metaCache;
        this.dataSecurity = dataSecurity;
        this.ingestQuota = ingestQuota;
        this.latestDatapointCache = latestDatapointCache;
        this.blockProducer = blockProducer;
        this.datapointIngestCounter = datapointIngestCounter;
        this.inFlightLimit = limits.getMaxInFlightDatapointsBinary();
        this.inFlight = new Semaphore(inFlightLimit);
    }

    /**
     * @param body the raw request body, one or more frames
     * @param declaredContentLength the request's Content-Length, or a negative number when chunked;
     *                              the size filter already charged that many bytes to the quota
     */
    public Summary ingest(byte[] body, long declaredContentLength) {
        if (!inFlight.tryAcquire()) {
            throw DatapointBlockRejectedException.tooManyInFlight(inFlightLimit);
        }
        try {
            return validateAndPublish(body, declaredContentLength);
        } finally {
            inFlight.release();
        }
    }

    private Summary validateAndPublish(byte[] body, long declaredContentLength) {
        String tenantId = TenantContext.getTenantId();
        List<DatapointFrame> frames = parse(body);

        Set<Long> ids = new LinkedHashSet<>();
        for (DatapointFrame f : frames) {
            for (long id : f.seriesIds()) {
                ids.add(id);
            }
        }
        Map<Long, SeriesMeta> meta = metaCache.resolve(tenantId, ids);
        List<Long> unknown = new ArrayList<>();
        for (Long id : ids) {
            if (!meta.containsKey(id)) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw DatapointBlockRejectedException.unknownTimeseries(unknown);
        }

        // Writing datapoints is a write to the series' dataset; check each dataset once.
        Set<Long> datasets = new HashSet<>();
        for (SeriesMeta m : meta.values()) {
            datasets.add(m.dataSetId());
        }
        for (Long dataset : datasets) {
            dataSecurity.assertCanWriteDataSet(dataset);
        }

        long totalRows = 0;
        long textRows = 0;
        long rawTotal = 0;
        for (DatapointFrame f : frames) {
            List<Long> wrongType = new ArrayList<>();
            List<Long> renamed = new ArrayList<>();
            for (Run run : f.runs()) {
                SeriesMeta m = meta.get(run.id());
                if (m.valueTypeId() != f.valueType().id()) {
                    wrongType.add(run.id());
                } else if (!m.externalId().equals(run.externalId())) {
                    renamed.add(run.id());
                }
            }
            if (!wrongType.isEmpty()) {
                throw DatapointBlockRejectedException.valueTypeMismatch(f.index(), wrongType);
            }
            if (!renamed.isEmpty()) {
                throw DatapointBlockRejectedException.externalIdMismatch(f.index(), renamed);
            }
            totalRows += f.rowCount();
            if (f.valueType().carriesText()) {
                textRows += f.rowCount();
            }
            rawTotal += f.rawLength();
        }

        // Quotas last, so a refused request charges nothing. The size filter charged the declared
        // (compressed) bytes; top up to the decompressed size so compression does not shrink the
        // allowance, and charge chunked bodies, which the filter never sees a length for, in full.
        ingestQuota.checkAndRecord(IngestQuotaService.QuotaMetric.DATAPOINTS, totalRows);
        if (textRows > 0) {
            ingestQuota.checkAndRecord(IngestQuotaService.QuotaMetric.TEXT_DATAPOINTS, textRows);
        }
        long topUp = rawTotal - Math.max(declaredContentLength, 0);
        if (topUp > 0) {
            ingestQuota.checkAndRecord(IngestQuotaService.QuotaMetric.BYTES, topUp);
        }

        for (DatapointFrame f : frames) {
            try {
                blockProducer.newMessage()
                        .property("tenantId", tenantId)
                        .property("valueTypeId", String.valueOf(f.valueType().id()))
                        .property("rows", String.valueOf(f.rowCount()))
                        .property("series", String.valueOf(f.seriesCount()))
                        .property("version", "1")
                        .property("codec", "1")
                        .property("compression", "1")
                        .value(f.frameBytes())
                        .send();
            } catch (PulsarClientException e) {
                throw new IllegalStateException("Publishing frame " + f.index() + " failed", e);
            }
        }

        for (DatapointFrame f : frames) {
            for (Run run : f.runs()) {
                int last = run.to() - 1;
                latestDatapointCache.update(run.externalId(), f.timestamp(last), f.valueAsString(last));
            }
        }
        datapointIngestCounter.recordIngested(tenantId, totalRows);
        return new Summary(frames.size(), totalRows, ids.size());
    }

    private List<DatapointFrame> parse(byte[] body) {
        List<DatapointFrame> frames;
        try {
            frames = DatapointFrame.parseEnvelopes(body);
        } catch (FrameFormatException e) {
            throw DatapointBlockRejectedException.from(e);
        }
        if (frames.size() == 1) {
            decode(frames.get(0));
            return frames;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> pending = new ArrayList<>(frames.size());
            for (DatapointFrame f : frames) {
                pending.add(pool.submit(() -> decode(f)));
            }
            for (Future<?> p : pending) {
                try {
                    p.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof DatapointBlockRejectedException rejected) {
                        throw rejected;
                    }
                    throw new IllegalStateException("Decoding a frame failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while decoding frames", e);
                }
            }
        }
        return frames;
    }

    private void decode(DatapointFrame f) {
        try {
            f.decode(codec);
        } catch (FrameFormatException e) {
            throw DatapointBlockRejectedException.from(e);
        }
    }
}
