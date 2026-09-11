// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.sdk.ingest.SeriesResolver.Resolved;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Sends datapoints as binary frames: resolves each series to its id and value type, parses the
 * values with the server's rules, sorts and de-duplicates per frame, compresses each frame with
 * zstd, packs frames into requests under the endpoint's caps and runs the requests through
 * {@link BatchExecutor}. A request the server refuses because a series is unknown or renamed
 * evicts those series from the resolver and is rebuilt and sent once more.
 */
public final class BinaryDatapointIngestor {

    static final String PATH = "/timeseries/data/binary";
    /** Leave headroom under the 4 MiB raw cap so the estimate never lands on the wrong side of it. */
    static final long FRAME_RAW_TARGET = 3L * 1024 * 1024 + 512 * 1024;
    static final long REQUEST_RAW_TARGET = FrameLimits.MAX_REQUEST_RAW_BYTES - FrameLimits.MAX_FRAME_RAW_BYTES;

    private final ApiHttp http;
    private final SeriesResolver resolver;

    public BinaryDatapointIngestor(ApiHttp http, SeriesResolver resolver) {
        this.http = http;
        this.resolver = resolver;
    }

    public IngestResult ingest(List<DatapointsCollection> data, BinaryIngestOptions options) {
        Prepared prepared = prepare(data, options);
        IngestResult live = send(prepared.requests(), options);

        // Evict and retry once for series the server no longer knows by that id or name.
        List<Request> stale = new ArrayList<>();
        for (IngestResult.BatchError error : live.errors()) {
            if (isStaleSeries(error)) {
                for (Request r : prepared.requests()) {
                    if (r.count() == error.datapointCount() && !stale.contains(r)) {
                        stale.add(r);
                        break;
                    }
                }
            }
        }
        if (!stale.isEmpty()) {
            Set<Long> ids = new LinkedHashSet<>();
            List<DatapointsCollection> again = new ArrayList<>();
            for (Request r : stale) {
                ids.addAll(r.seriesIds());
                again.addAll(r.collections());
            }
            resolver.evict(ids);
            Prepared rebuilt = prepare(again, options);
            IngestResult retry = send(rebuilt.requests(), options);
            List<IngestResult.BatchError> errors = new ArrayList<>();
            for (IngestResult.BatchError e : live.errors()) {
                if (!isStaleSeries(e)) errors.add(e);
            }
            errors.addAll(retry.errors());
            errors.addAll(prepared.localErrors());
            errors.addAll(rebuilt.localErrors());
            long succeeded = live.succeeded() + retry.succeeded();
            long failed = live.failed() - staleCount(live) + retry.failed() + localFailed(prepared) + localFailed(rebuilt);
            return new IngestResult(succeeded, failed, errors);
        }
        if (prepared.localErrors().isEmpty()) {
            return live;
        }
        List<IngestResult.BatchError> errors = new ArrayList<>(live.errors());
        errors.addAll(prepared.localErrors());
        return new IngestResult(live.succeeded(), live.failed() + localFailed(prepared), errors);
    }

    private static boolean isStaleSeries(IngestResult.BatchError error) {
        if (error.body() == null) return false;
        return (error.statusCode() == 404 && error.body().contains("unknown-timeseries"))
                || (error.statusCode() == 422 && error.body().contains("external-id-mismatch"));
    }

    private static long staleCount(IngestResult result) {
        long n = 0;
        for (IngestResult.BatchError e : result.errors()) {
            if (isStaleSeries(e)) n += e.datapointCount();
        }
        return n;
    }

    private static long localFailed(Prepared p) {
        long n = 0;
        for (IngestResult.BatchError e : p.localErrors()) n += e.datapointCount();
        return n;
    }

    /** One request body: its frames, how many points it carries and which series. */
    record Request(byte[] body, int count, Set<Long> seriesIds, List<DatapointsCollection> collections) {
    }

    record Prepared(List<Request> requests, List<IngestResult.BatchError> localErrors) {
    }

    private IngestResult send(List<Request> requests, BinaryIngestOptions options) {
        List<BatchExecutor.Task> tasks = new ArrayList<>(requests.size());
        for (Request r : requests) {
            tasks.add(new BatchExecutor.Task(r.count(), () -> http.postBytes(PATH, r.body(), FrameLimits.MEDIA_TYPE, Map.of())));
        }
        return BatchExecutor.execute(tasks, options.executorOptions());
    }

    /**
     * Resolve, parse, frame and pack. Public for the buffered mode's tests; the per-series parse
     * failures come back as local errors rather than exceptions so one bad point does not stop
     * the rest.
     */
    Prepared prepare(List<DatapointsCollection> data, BinaryIngestOptions options) {
        List<IngestResult.BatchError> localErrors = new ArrayList<>();
        Map<DatapointsCollection, Resolved> series = resolve(data, localErrors);

        // One open writer per value type; a writer becomes a frame when it reaches a cap.
        Map<DatapointValueType, DatapointFrameWriter> open = new EnumMap<>(DatapointValueType.class);
        Map<DatapointValueType, Set<Long>> openSeries = new EnumMap<>(DatapointValueType.class);
        Map<DatapointValueType, List<DatapointsCollection>> openCollections = new EnumMap<>(DatapointValueType.class);
        List<PendingFrame> pending = new ArrayList<>();

        for (DatapointsCollection collection : data) {
            Resolved r = series.get(collection);
            if (r == null || collection.getDatapoints() == null) continue;
            DatapointValueType type = r.type();
            int bad = 0;
            String firstProblem = null;
            for (DatapointString dp : collection.getDatapoints()) {
                DatapointFrameWriter w = open.get(type);
                Set<Long> ids = openSeries.get(type);
                if (w == null || w.rowCount() >= FrameLimits.maxRows(type) || w.estimatedRawBytes() >= FRAME_RAW_TARGET
                        || (ids.size() >= FrameLimits.MAX_SERIES_PER_FRAME && !ids.contains(r.id()))) {
                    if (w != null) {
                        pending.add(new PendingFrame(w, openCollections.get(type)));
                    }
                    w = DatapointFrameWriter.forType(type);
                    ids = new LinkedHashSet<>();
                    open.put(type, w);
                    openSeries.put(type, ids);
                    openCollections.put(type, new ArrayList<>());
                }
                if (ids.add(r.id())) {
                    w.series(r.id(), r.externalId());
                }
                if (!openCollections.get(type).contains(collection)) openCollections.get(type).add(collection);
                try {
                    w.add(r.id(), DateTimeHandler.toEpochUTCTime(dp.getTimestamp()), dp.getValue());
                } catch (RuntimeException e) {
                    bad++;
                    if (firstProblem == null) firstProblem = e.getMessage();
                }
            }
            if (bad > 0) {
                localErrors.add(new IngestResult.BatchError(bad, 422,
                        "series " + r.externalId() + ": " + bad + " value(s) not valid for " + type + " (" + firstProblem + ")"));
            }
        }
        for (Map.Entry<DatapointValueType, DatapointFrameWriter> e : open.entrySet()) {
            if (e.getValue().rowCount() > 0) {
                pending.add(new PendingFrame(e.getValue(), openCollections.get(e.getKey())));
            }
        }

        List<BuiltFrame> frames = build(pending, options);
        return new Prepared(pack(frames), localErrors);
    }

    private Map<DatapointsCollection, Resolved> resolve(List<DatapointsCollection> data, List<IngestResult.BatchError> localErrors) {
        Set<String> externalIds = new LinkedHashSet<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (DatapointsCollection c : data) {
            if (c.getExternalId() != null) externalIds.add(c.getExternalId());
            else if (c.getId() != null) ids.add(c.getId());
        }
        Map<String, Resolved> byExternalId = externalIds.isEmpty() ? Map.of() : resolver.resolveExternalIds(externalIds);
        Map<Long, Resolved> byId = ids.isEmpty() ? Map.of() : resolver.resolveIds(ids);
        Map<DatapointsCollection, Resolved> out = new HashMap<>();
        for (DatapointsCollection c : data) {
            Resolved r = c.getExternalId() != null ? byExternalId.get(c.getExternalId())
                    : c.getId() != null ? byId.get(c.getId()) : null;
            if (r == null) {
                int n = c.getDatapoints() == null ? 0 : c.getDatapoints().size();
                if (n > 0) {
                    localErrors.add(new IngestResult.BatchError(n, 404,
                            "series " + (c.getExternalId() != null ? c.getExternalId() : String.valueOf(c.getId()))
                                    + " does not exist or is not readable"));
                }
            } else {
                out.put(c, r);
            }
        }
        return out;
    }

    private record PendingFrame(DatapointFrameWriter writer, List<DatapointsCollection> collections) {
    }

    private record BuiltFrame(byte[] bytes, int rows, int rawLength, Set<Long> seriesIds, List<DatapointsCollection> collections) {
    }

    private static List<BuiltFrame> build(List<PendingFrame> pending, BinaryIngestOptions options) {
        ZstdPayloadCodec codec = new ZstdPayloadCodec(options.zstdLevel());
        List<BuiltFrame> frames = new ArrayList<>(pending.size());
        if (!options.compressInParallel() || pending.size() == 1) {
            for (PendingFrame p : pending) frames.add(buildOne(p, codec));
            return frames;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<BuiltFrame>> futures = new ArrayList<>(pending.size());
            for (PendingFrame p : pending) futures.add(pool.submit(() -> buildOne(p, codec)));
            for (Future<BuiltFrame> f : futures) {
                try {
                    frames.add(f.get());
                } catch (ExecutionException e) {
                    throw new DatahubApiException(0, "building a datapoint frame failed: " + e.getCause().getMessage(), null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DatahubApiException(0, "interrupted while building datapoint frames", null);
                }
            }
        }
        return frames;
    }

    private static BuiltFrame buildOne(PendingFrame p, ZstdPayloadCodec codec) {
        byte[] bytes = p.writer().build(codec);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int rows = header.getInt(8);
        int rawLength = header.getInt(24);
        Set<Long> ids = new LinkedHashSet<>();
        int seriesCount = header.getInt(12);
        int pos = FrameLimits.HEADER_BYTES;
        for (int s = 0; s < seriesCount; s++) {
            ids.add(header.getLong(pos));
            pos += 8;
            int len = 0, shift = 0, b;
            do {
                b = bytes[pos++] & 0xFF;
                len |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            pos += len;
        }
        return new BuiltFrame(bytes, rows, rawLength, ids, p.collections());
    }

    /** Greedy packing under the request caps: frame count and decompressed total. */
    private static List<Request> pack(List<BuiltFrame> frames) {
        List<Request> requests = new ArrayList<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int count = 0;
        int inRequest = 0;
        long raw = 0;
        Set<Long> ids = new LinkedHashSet<>();
        List<DatapointsCollection> collections = new ArrayList<>();
        for (BuiltFrame f : frames) {
            if (inRequest > 0 && (inRequest >= FrameLimits.MAX_FRAMES_PER_REQUEST || raw + f.rawLength() > REQUEST_RAW_TARGET)) {
                requests.add(new Request(body.toByteArray(), count, ids, collections));
                body = new ByteArrayOutputStream();
                count = 0;
                inRequest = 0;
                raw = 0;
                ids = new LinkedHashSet<>();
                collections = new ArrayList<>();
            }
            body.writeBytes(f.bytes());
            count += f.rows();
            inRequest++;
            raw += f.rawLength();
            ids.addAll(f.seriesIds());
            for (DatapointsCollection c : f.collections()) {
                if (!collections.contains(c)) collections.add(c);
            }
        }
        if (inRequest > 0) {
            requests.add(new Request(body.toByteArray(), count, ids, collections));
        }
        return requests;
    }
}
