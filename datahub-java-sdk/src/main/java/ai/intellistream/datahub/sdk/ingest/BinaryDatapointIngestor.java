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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Sends datapoints as binary frames: resolves each series to its id and value type, parses the
 * values with the server's rules, sorts and de-duplicates per frame, compresses each frame with
 * zstd, packs frames into requests under the endpoint's caps and runs the requests through
 * {@link BatchExecutor}. A request the server refuses because a series is unknown or renamed
 * evicts those series from the resolver, and the points it carried are rebuilt and sent once more.
 */
public final class BinaryDatapointIngestor {

    static final String PATH = "/timeseries/data/binary";
    /** Leave headroom under the 4 MiB raw cap so the estimate never lands on the wrong side of it. */
    static final long FRAME_RAW_TARGET = 3L * 1024 * 1024 + 512 * 1024;
    /** The same headroom under the cap on a frame as sent, which a large directory reaches first. */
    static final long FRAME_BYTES_TARGET = FrameLimits.MAX_FRAME_BYTES - 256L * 1024;
    static final long REQUEST_RAW_TARGET = FrameLimits.MAX_REQUEST_RAW_BYTES - FrameLimits.MAX_FRAME_RAW_BYTES;

    private final ApiHttp http;
    private final SeriesResolver resolver;

    public BinaryDatapointIngestor(ApiHttp http, SeriesResolver resolver) {
        this.http = http;
        this.resolver = resolver;
    }

    public IngestResult ingest(List<DatapointsCollection> data, BinaryIngestOptions options) {
        List<Slice> whole = new ArrayList<>(data.size());
        for (DatapointsCollection c : data) {
            whole.add(new Slice(c, 0, c.getDatapoints() == null ? 0 : c.getDatapoints().size()));
        }
        Prepared prepared = prepare(whole, options);
        AtomicReferenceArray<DatahubApiException> failures = new AtomicReferenceArray<>(prepared.requests().size());
        IngestResult live = send(prepared.requests(), options, failures);

        // Evict and retry once for series the server no longer knows by that id or name. A refusal
        // is matched to its request by position, since requests of one size are the rule once frames
        // fill, and only the points that request carried go again.
        Set<Long> ids = new LinkedHashSet<>();
        List<Slice> again = new ArrayList<>();
        for (int i = 0; i < failures.length(); i++) {
            DatahubApiException e = failures.get(i);
            if (e != null && isStaleSeries(e.statusCode(), e.body())) {
                ids.addAll(prepared.requests().get(i).seriesIds());
                again.addAll(prepared.requests().get(i).slices());
            }
        }
        if (!again.isEmpty()) {
            resolver.evict(ids);
            Prepared rebuilt = prepare(again, options);
            IngestResult retry = send(rebuilt.requests(), options, new AtomicReferenceArray<>(rebuilt.requests().size()));
            // The rebuild parses the same points again, so its value errors repeat ones already
            // counted. A series it cannot resolve any more is new, and those points fail here.
            List<IngestResult.BatchError> unresolved = new ArrayList<>();
            for (IngestResult.BatchError e : rebuilt.localErrors()) {
                if (e.statusCode() == 404) unresolved.add(e);
            }
            List<IngestResult.BatchError> errors = new ArrayList<>();
            for (IngestResult.BatchError e : live.errors()) {
                if (!isStaleSeries(e.statusCode(), e.body())) errors.add(e);
            }
            errors.addAll(retry.errors());
            errors.addAll(prepared.localErrors());
            errors.addAll(unresolved);
            long succeeded = live.succeeded() + retry.succeeded();
            long failed = live.failed() - staleCount(live) + retry.failed() + count(prepared.localErrors()) + count(unresolved);
            return new IngestResult(succeeded, failed, errors);
        }
        if (prepared.localErrors().isEmpty()) {
            return live;
        }
        List<IngestResult.BatchError> errors = new ArrayList<>(live.errors());
        errors.addAll(prepared.localErrors());
        return new IngestResult(live.succeeded(), live.failed() + count(prepared.localErrors()), errors);
    }

    private static boolean isStaleSeries(int statusCode, String body) {
        if (body == null) return false;
        return (statusCode == 404 && body.contains("unknown-timeseries"))
                || (statusCode == 422 && body.contains("external-id-mismatch"));
    }

    private static long staleCount(IngestResult result) {
        long n = 0;
        for (IngestResult.BatchError e : result.errors()) {
            if (isStaleSeries(e.statusCode(), e.body())) n += e.datapointCount();
        }
        return n;
    }

    private static long count(List<IngestResult.BatchError> errors) {
        long n = 0;
        for (IngestResult.BatchError e : errors) n += e.datapointCount();
        return n;
    }

    /** The points of one collection from {@code from} inclusive to {@code to} exclusive, in its own order. */
    record Slice(DatapointsCollection collection, int from, int to) {
    }

    /** One request body: its frames, how many points it carries, which series, and which points. */
    record Request(byte[] body, int count, Set<Long> seriesIds, List<Slice> slices) {
    }

    record Prepared(List<Request> requests, List<IngestResult.BatchError> localErrors) {
    }

    /** Sends every request, leaving in {@code failures}, at its position, the error each one ended with. */
    private IngestResult send(List<Request> requests, BinaryIngestOptions options,
                              AtomicReferenceArray<DatahubApiException> failures) {
        List<BatchExecutor.Task> tasks = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            Request r = requests.get(i);
            int position = i;
            tasks.add(new BatchExecutor.Task(r.count(), () -> {
                try {
                    http.postBytes(PATH, r.body(), FrameLimits.MEDIA_TYPE, Map.of());
                    failures.set(position, null);
                } catch (DatahubApiException e) {
                    failures.set(position, e);
                    throw e;
                }
            }));
        }
        return BatchExecutor.execute(tasks, options.executorOptions());
    }

    /**
     * Resolve, parse, frame and pack. Public for the buffered mode's tests; the per-series parse
     * failures come back as local errors rather than exceptions so one bad point does not stop
     * the rest.
     */
    Prepared prepare(List<Slice> data, BinaryIngestOptions options) {
        List<IngestResult.BatchError> localErrors = new ArrayList<>();
        Map<DatapointsCollection, Resolved> series = resolve(data, localErrors);

        // One open writer per value type; a writer becomes a frame when it reaches a cap, and knows
        // which run of which collection's points it holds.
        Map<DatapointValueType, DatapointFrameWriter> open = new EnumMap<>(DatapointValueType.class);
        Map<DatapointValueType, Set<Long>> openSeries = new EnumMap<>(DatapointValueType.class);
        Map<DatapointValueType, List<Slice>> openSlices = new EnumMap<>(DatapointValueType.class);
        List<PendingFrame> pending = new ArrayList<>();

        for (Slice slice : data) {
            DatapointsCollection collection = slice.collection();
            Resolved r = series.get(collection);
            if (r == null || collection.getDatapoints() == null || slice.from() == slice.to()) continue;
            DatapointValueType type = r.type();
            int bad = 0;
            String firstProblem = null;
            int runStart = slice.from();
            int i = slice.from();
            for (DatapointString dp : collection.getDatapoints().subList(slice.from(), slice.to())) {
                DatapointFrameWriter w = open.get(type);
                Set<Long> ids = openSeries.get(type);
                if (w == null || w.rowCount() >= FrameLimits.maxRows(type) || w.estimatedRawBytes() >= FRAME_RAW_TARGET
                        || w.estimatedFrameBytes() >= FRAME_BYTES_TARGET
                        || (ids.size() >= FrameLimits.MAX_SERIES_PER_FRAME && !ids.contains(r.id()))) {
                    if (w != null) {
                        if (i > runStart) openSlices.get(type).add(new Slice(collection, runStart, i));
                        pending.add(new PendingFrame(w, openSlices.get(type)));
                    }
                    runStart = i;
                    w = DatapointFrameWriter.forType(type);
                    ids = new LinkedHashSet<>();
                    open.put(type, w);
                    openSeries.put(type, ids);
                    openSlices.put(type, new ArrayList<>());
                }
                if (ids.add(r.id())) {
                    w.series(r.id(), r.externalId());
                }
                try {
                    w.add(r.id(), DateTimeHandler.toEpochUTCTime(dp.getTimestamp()), dp.getValue());
                } catch (RuntimeException e) {
                    bad++;
                    if (firstProblem == null) firstProblem = e.getMessage();
                }
                i++;
            }
            openSlices.get(type).add(new Slice(collection, runStart, slice.to()));
            if (bad > 0) {
                localErrors.add(new IngestResult.BatchError(bad, 422,
                        "series " + r.externalId() + ": " + bad + " value(s) not valid for " + type + " (" + firstProblem + ")"));
            }
        }
        for (Map.Entry<DatapointValueType, DatapointFrameWriter> e : open.entrySet()) {
            if (e.getValue().rowCount() > 0) {
                pending.add(new PendingFrame(e.getValue(), openSlices.get(e.getKey())));
            }
        }

        List<BuiltFrame> frames = build(pending, options);
        return new Prepared(pack(frames), localErrors);
    }

    private Map<DatapointsCollection, Resolved> resolve(List<Slice> data, List<IngestResult.BatchError> localErrors) {
        Set<String> externalIds = new LinkedHashSet<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (Slice s : data) {
            DatapointsCollection c = s.collection();
            if (c.getExternalId() != null) externalIds.add(c.getExternalId());
            else if (c.getId() != null) ids.add(c.getId());
        }
        Map<String, Resolved> byExternalId = externalIds.isEmpty() ? Map.of() : resolver.resolveExternalIds(externalIds);
        Map<Long, Resolved> byId = ids.isEmpty() ? Map.of() : resolver.resolveIds(ids);
        // By identity: a collection's equality is its content, every point of it.
        Map<DatapointsCollection, Resolved> out = new IdentityHashMap<>();
        for (Slice s : data) {
            DatapointsCollection c = s.collection();
            Resolved r = c.getExternalId() != null ? byExternalId.get(c.getExternalId())
                    : c.getId() != null ? byId.get(c.getId()) : null;
            if (r == null) {
                int n = c.getDatapoints() == null ? 0 : s.to() - s.from();
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

    private record PendingFrame(DatapointFrameWriter writer, List<Slice> slices) {
    }

    private record BuiltFrame(byte[] bytes, int rows, int rawLength, Set<Long> seriesIds, List<Slice> slices) {
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
        return new BuiltFrame(bytes, rows, rawLength, ids, p.slices());
    }

    /** Greedy packing under the request caps: frame count and decompressed total. */
    private static List<Request> pack(List<BuiltFrame> frames) {
        List<Request> requests = new ArrayList<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int count = 0;
        int inRequest = 0;
        long raw = 0;
        Set<Long> ids = new LinkedHashSet<>();
        List<Slice> slices = new ArrayList<>();
        for (BuiltFrame f : frames) {
            if (inRequest > 0 && (inRequest >= FrameLimits.MAX_FRAMES_PER_REQUEST || raw + f.rawLength() > REQUEST_RAW_TARGET)) {
                requests.add(new Request(body.toByteArray(), count, ids, slices));
                body = new ByteArrayOutputStream();
                count = 0;
                inRequest = 0;
                raw = 0;
                ids = new LinkedHashSet<>();
                slices = new ArrayList<>();
            }
            body.writeBytes(f.bytes());
            count += f.rows();
            inRequest++;
            raw += f.rawLength();
            ids.addAll(f.seriesIds());
            slices.addAll(f.slices());
        }
        if (inRequest > 0) {
            requests.add(new Request(body.toByteArray(), count, ids, slices));
        }
        return requests;
    }
}
