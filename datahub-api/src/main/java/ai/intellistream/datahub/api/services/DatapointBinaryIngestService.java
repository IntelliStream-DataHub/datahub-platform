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
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository.IngestTarget;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 * <p>Order of work, and why: an in-flight permit before the body is read, the envelopes and
 * directories next (cheap, no payload touched), then the payloads in parallel, then one lookup of
 * every distinct series, the dataset write check once per distinct dataset, the value type and
 * external id of every series against what the frame claims, the quotas, and only then the
 * publish. A request refused anywhere before the publish sent nothing.
 *
 * <p>The publish itself is not atomic. Frames go out one at a time and one message each, since
 * the broker's message size rules out sending a request as one, so a send that fails partway
 * leaves the frames before it published. That request is still safe to retry whole: a datapoint
 * is stored once per series and timestamp however often it arrives. What must not be counted
 * twice is counted per frame as it lands instead: the datapoint quotas, the latest-value cache
 * and the ingest counter.
 */
@Service
@Slf4j
public class DatapointBinaryIngestService {

    public record Summary(int frames, long rows, int series) {
    }

    /** Ids per query, so a request naming very many series stays under Postgres' bind-parameter limit. */
    static final int QUERY_CHUNK = 5_000;

    private final TimeseriesRepository timeseriesRepository;
    private final DataSecurity dataSecurity;
    private final IngestQuotaService ingestQuota;
    private final LatestDatapointCache latestDatapointCache;
    private final Producer<byte[]> blockProducer;
    private final LiveIngestCounter datapointIngestCounter;
    private final int inFlightLimit;
    private final Semaphore inFlight;
    private final PayloadCodec codec = new ZstdPayloadCodec();

    public DatapointBinaryIngestService(TimeseriesRepository timeseriesRepository,
                                        DataSecurity dataSecurity,
                                        IngestQuotaService ingestQuota,
                                        LatestDatapointCache latestDatapointCache,
                                        @Qualifier("allDatapointBlockProducer") Producer<byte[]> blockProducer,
                                        @Qualifier("datapointIngestCounter") LiveIngestCounter datapointIngestCounter,
                                        LimitsProperties limits) {
        this.timeseriesRepository = timeseriesRepository;
        this.dataSecurity = dataSecurity;
        this.ingestQuota = ingestQuota;
        this.latestDatapointCache = latestDatapointCache;
        this.blockProducer = blockProducer;
        this.datapointIngestCounter = datapointIngestCounter;
        this.inFlightLimit = limits.getMaxInFlightDatapointsBinary();
        this.inFlight = new Semaphore(inFlightLimit);
    }

    /**
     * @param body the request body, one or more frames; read only once a permit is held, so the
     *             in-flight cap also bounds how many bodies are buffered, and a refused request is
     *             answered before its body arrives
     * @param declaredContentLength the request's Content-Length, or a negative number when chunked;
     *                              the size filter already charged that many bytes to the quota
     */
    public Summary ingest(InputStream body, long declaredContentLength) throws IOException {
        if (!inFlight.tryAcquire()) {
            throw DatapointBlockRejectedException.tooManyInFlight(inFlightLimit);
        }
        try {
            return validateAndPublish(body.readAllBytes(), declaredContentLength);
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
        Map<Long, IngestTarget> series = findSeries(ids);
        List<Long> unknown = new ArrayList<>();
        for (Long id : ids) {
            if (!series.containsKey(id)) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw DatapointBlockRejectedException.unknownTimeseries(unknown);
        }

        // Writing datapoints is a write to the series' dataset; check each dataset once.
        Set<Long> datasets = new HashSet<>();
        for (IngestTarget target : series.values()) {
            datasets.add(target.dataSetId());
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
                IngestTarget target = series.get(run.id());
                if (target.valueTypeId() != f.valueType().id()) {
                    wrongType.add(run.id());
                } else if (!target.externalId().equals(run.externalId())) {
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

        // Quotas last, so a refused request charges nothing. The rows are checked for the whole
        // request here and charged below as each frame lands, so a publish that fails partway is
        // not billed for the frames it never sent. Bytes are charged per attempt, as the size filter
        // already charged the declared (compressed) ones: top up to the decompressed size so
        // compression does not shrink the allowance, and charge chunked bodies, which the filter
        // never sees a length for, in full.
        ingestQuota.check(IngestQuotaService.QuotaMetric.DATAPOINTS, totalRows);
        if (textRows > 0) {
            ingestQuota.check(IngestQuotaService.QuotaMetric.TEXT_DATAPOINTS, textRows);
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
                throw new IllegalStateException("Publishing frame " + f.index() + " of " + frames.size()
                        + " failed; the frames before it were published", e);
            }
            ingestQuota.record(IngestQuotaService.QuotaMetric.DATAPOINTS, f.rowCount());
            if (f.valueType().carriesText()) {
                ingestQuota.record(IngestQuotaService.QuotaMetric.TEXT_DATAPOINTS, f.rowCount());
            }
            for (Run run : f.runs()) {
                int last = run.to() - 1;
                latestDatapointCache.update(run.externalId(), f.timestamp(last), f.valueAsString(last));
            }
            datapointIngestCounter.recordIngested(tenantId, f.rowCount());
        }
        return new Summary(frames.size(), totalRows, ids.size());
    }

    private Map<Long, IngestTarget> findSeries(Collection<Long> ids) {
        List<Long> wanted = List.copyOf(ids);
        Map<Long, IngestTarget> found = new HashMap<>();
        for (int from = 0; from < wanted.size(); from += QUERY_CHUNK) {
            List<Long> chunk = wanted.subList(from, Math.min(wanted.size(), from + QUERY_CHUNK));
            for (IngestTarget target : timeseriesRepository.findIngestTargetsByIdIn(chunk)) {
                found.put(target.id(), target);
            }
        }
        return found;
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
