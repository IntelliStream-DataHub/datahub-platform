// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.ingest.BinaryIngestOptions;
import ai.intellistream.datahub.sdk.ingest.IngestResult;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON against binary datapoint ingest, through the SDK, on a live platform.
 *
 * <p>Both paths carry the same generated points to their own set of series, so the two runs differ
 * only in transport. What is reported per path: wall time and points per second, per-request
 * latency, bytes actually on the wire (counted by a TCP relay, not estimated), the client's peak
 * heap, and the api's and consumer's peak resident memory and CPU. Ingest ends when the api has
 * accepted everything; the settle time afterwards is how long Pulsar and the consumer took to make
 * the rows readable, which is the number that matters for "when can I query it".
 *
 * <p>Size is {@code DATAHUB_BENCH_POINTS} (default 100 million) across
 * {@code DATAHUB_BENCH_SERIES} series. Needs a running stack; see README.md.
 */
@Tag("performance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestBenchmarkIT {

    private static final long POINTS = LiveStack.envLong("DATAHUB_BENCH_POINTS", 100_000_000L);
    private static final int SERIES = LiveStack.envInt("DATAHUB_BENCH_SERIES", 100);
    /** Points held in memory at once. The client cannot materialise a hundred million at a time. */
    private static final int CHUNK = LiveStack.envInt("DATAHUB_BENCH_CHUNK", 1_000_000);
    private static final Duration SETTLE = Duration.ofMinutes(LiveStack.envLong("DATAHUB_BENCH_SETTLE_MINUTES", 30));
    private static final String TABLE = "datapoints_float32";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private DatahubClient direct;
    private String runId;
    private final List<String> created = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();

    private record Result(String path, long points, double ingestSeconds, double settleSeconds,
                          long wireBytesOut, long wireBytesIn, Latencies latencies,
                          long clientPeakHeap, long apiPeakRss, double apiCpuSeconds,
                          long consumerPeakRss, double consumerCpuSeconds) {

        double pointsPerSecond() {
            return points / ingestSeconds;
        }

        double bytesPerPoint() {
            return wireBytesOut / (double) points;
        }
    }

    @BeforeAll
    void setUp() {
        LiveStack.requireReachable();
        direct = LiveStack.client();
        runId = "bench_" + Long.toString(System.currentTimeMillis(), 36);
    }

    @AfterAll
    void tearDown() {
        printReport();
        if (direct == null || created.isEmpty() || LiveStack.env("DATAHUB_BENCH_KEEP", null) != null) {
            return;
        }
        try {
            direct.timeseries().delete(created.stream()
                    .map(ai.intellistream.datahub.models.IdCollection::createFromExternalId).toList());
        } catch (RuntimeException e) {
            System.err.println("cleanup failed: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("JSON and binary ingest, same points, measured end to end")
    void compareIngestPaths() throws Exception {
        System.out.printf(Locale.ROOT, "%n=== %,d points across %,d series, %,d per chunk ===%n",
                POINTS, SERIES, CHUNK);

        results.add(run("JSON", false));
        results.add(run("binary", true));

        // Both paths must have stored everything; a fast path that loses rows is not a fast path.
        for (Result r : results) {
            assertThat(r.points()).isEqualTo(POINTS);
        }
    }

    private Result run(String label, boolean binary) throws Exception {
        List<String> seriesIds = new ArrayList<>(SERIES);
        List<Long> internalIds = new ArrayList<>(SERIES);
        for (int i = 0; i < SERIES; i++) {
            String externalId = runId + "_" + (binary ? "bin" : "json") + "_" + i;
            internalIds.add(createSeries(externalId));
            seriesIds.add(externalId);
        }

        long apiPid = ProcessSampler.pidListeningOn(apiPort());
        long consumerPid = consumerPid();

        try (ByteCountingProxy proxy = new ByteCountingProxy(LiveStack.baseUrl())) {
            // The SDK talks to the relay, so every byte it sends is counted, headers included.
            DatahubClient through = LiveStack.client(proxy.baseUrl());
            Latencies latencies = new Latencies();

            // Warm the JIT and the connection pool so the first chunk does not pay for both.
            warmUp(through, seriesIds.getFirst(), binary);
            proxy.reset();

            System.gc();
            long heapBefore = usedHeap();
            long peakHeap = heapBefore;

            try (ProcessSampler api = ProcessSampler.of(apiPid);
                 ProcessSampler consumer = ProcessSampler.of(consumerPid)) {

                long sent = 0;
                long startNanos = System.nanoTime();
                long offset = 0;
                while (sent < POINTS) {
                    int thisChunk = (int) Math.min(CHUNK, POINTS - sent);
                    List<DatapointsCollection> batch = generate(seriesIds, offset, thisChunk);

                    long t0 = System.nanoTime();
                    IngestResult result = binary
                            ? through.timeseries().ingestBinary(batch, BinaryIngestOptions.builder().build())
                            : through.timeseries().ingest(batch);
                    latencies.record(System.nanoTime() - t0);

                    if (!result.isComplete()) {
                        throw new AssertionError(label + " ingest failed at " + sent + ": " + result
                                + (result.errors().isEmpty() ? "" : " first error: " + result.errors().getFirst()));
                    }
                    sent += thisChunk;
                    offset += thisChunk / SERIES;
                    peakHeap = Math.max(peakHeap, usedHeap());

                    if (sent % (CHUNK * 10L) == 0 || sent == POINTS) {
                        double elapsed = (System.nanoTime() - startNanos) / 1e9;
                        System.out.printf(Locale.ROOT, "  %s: %,d / %,d points, %.0f pts/s%n",
                                label, sent, POINTS, sent / elapsed);
                    }
                }
                double ingestSeconds = (System.nanoTime() - startNanos) / 1e9;

                // Accepted is not stored: wait for the rows to become readable.
                long settleStart = System.nanoTime();
                awaitRows(internalIds, POINTS);
                double settleSeconds = (System.nanoTime() - settleStart) / 1e9;

                return new Result(label, POINTS, ingestSeconds, settleSeconds,
                        proxy.bytesSent(), proxy.bytesReceived(), latencies, peakHeap - heapBefore,
                        api == null ? 0 : api.peakRssBytes(), api == null ? 0 : api.cpuSeconds(),
                        consumer == null ? 0 : consumer.peakRssBytes(),
                        consumer == null ? 0 : consumer.cpuSeconds());
            }
        }
    }

    private void warmUp(DatahubClient client, String externalId, boolean binary) {
        List<DatapointsCollection> warm = List.of(collection(externalId, generatePoints(0, 1000, 0)));
        IngestResult result = binary ? client.timeseries().ingestBinary(warm) : client.timeseries().ingest(warm);
        if (!result.isComplete()) {
            throw new AssertionError("warm-up failed: " + result);
        }
    }

    /**
     * One chunk: {@code count} points spread evenly over the series, one per second each. Every
     * series gets its own signal. Handing them all identical values would let zstd compress the
     * repeats across series and report a wire size no real fleet of sensors would produce.
     */
    private static List<DatapointsCollection> generate(List<String> seriesIds, long offsetSeconds, int count) {
        int perSeries = count / seriesIds.size();
        List<DatapointsCollection> batch = new ArrayList<>(seriesIds.size());
        for (int s = 0; s < seriesIds.size(); s++) {
            batch.add(collection(seriesIds.get(s), generatePoints(offsetSeconds, perSeries, s)));
        }
        return batch;
    }

    /**
     * A slow sine plus a little noise, which is roughly what an analog sensor produces: the trend
     * is what Gorilla and DoubleDelta are built for, and the noise is what stops the series being
     * unrealistically compressible. Without it the values repeat exactly every period and zstd
     * reports a ratio no real deployment would see, which would flatter the binary path.
     *
     * <p>Deterministic, so two runs generate the same bytes and the numbers can be compared.
     */
    private static List<DatapointString> generatePoints(long offsetSeconds, int count, int seriesIndex) {
        List<DatapointString> points = new ArrayList<>(count);
        double base = 150.0 + seriesIndex * 0.7;
        double phase = seriesIndex * 0.37;
        for (int i = 0; i < count; i++) {
            long t = offsetSeconds + i;
            long epochMillis = START.plusSeconds(t).toEpochMilli();
            // splitmix64, inlined: a cheap deterministic bit mixer, no allocation per point. The
            // series index goes into the seed so no two series emit the same sequence.
            long z = (t + seriesIndex * 0x5851F42D4C957F2DL) * 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            double noise = ((z >>> 40) / (double) (1 << 24)) - 0.5;
            float value = (float) (base + 20.0 * Math.sin(t / 600.0 + phase) + noise);
            points.add(new DatapointString(Long.toString(epochMillis), Float.toString(value)));
        }
        return points;
    }

    private static DatapointsCollection collection(String externalId, List<DatapointString> points) {
        DatapointsCollection collection = new DatapointsCollection();
        collection.setExternalId(externalId);
        collection.setDatapoints(points);
        return collection;
    }

    private long createSeries(String externalId) {
        Timeseries series = new Timeseries();
        series.setExternalId(externalId);
        series.setName(externalId);
        series.setValueType("float32");
        series.setUnit("celsius");
        var response = direct.timeseries().create(List.of(series));
        created.add(externalId);
        return response.getItems().iterator().next().getId();
    }

    private void awaitRows(List<Long> ids, long expected) {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        long last = -1;
        while (System.nanoTime() < deadline) {
            long count = LiveStack.clickHouseCount(TABLE, ids);
            if (count >= expected) {
                return;
            }
            if (count != last) {
                last = count;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("only " + LiveStack.clickHouseCount(TABLE, ids)
                + " of " + expected + " rows became readable within " + SETTLE);
    }

    private static long usedHeap() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getUsed();
    }

    private static int apiPort() {
        String base = LiveStack.baseUrl();
        java.net.URI uri = java.net.URI.create(base);
        return uri.getPort() > 0 ? uri.getPort() : 80;
    }

    /** The consumer has no port of its own worth probing, so it is named by env or skipped. */
    private static long consumerPid() {
        String configured = LiveStack.env("DATAHUB_CONSUMER_PID", null);
        if (configured != null) {
            return Long.parseLong(configured.trim());
        }
        try {
            Process process = new ProcessBuilder("bash", "-c",
                    "pgrep -f datahub-stateless-consumer | head -n1").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return out.isEmpty() ? -1 : Long.parseLong(out.lines().findFirst().orElse("-1").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void printReport() {
        if (results.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder("\n=== datapoint ingest: JSON against binary ===\n");
        out.append(String.format(Locale.ROOT, "%,d points across %,d series, float32%n%n", POINTS, SERIES));
        row(out, "metric", results.stream().map(Result::path).toList());
        out.append("\n");
        row(out, "ingest wall time (s)", fmt("%.1f", Result::ingestSeconds));
        row(out, "points per second", fmt("%,.0f", Result::pointsPerSecond));
        row(out, "settle to readable (s)", fmt("%.1f", Result::settleSeconds));
        row(out, "wire bytes out", fmt("%,d", r -> (double) r.wireBytesOut()));
        row(out, "wire bytes per point", fmt("%.2f", Result::bytesPerPoint));
        row(out, "wire bytes in", fmt("%,d", r -> (double) r.wireBytesIn()));
        row(out, "requests", fmt("%,d", r -> (double) r.latencies().count()));
        row(out, "latency mean (ms)", fmt("%,.0f", r -> r.latencies().meanMillis()));
        row(out, "latency p50 (ms)", fmt("%,.0f", r -> r.latencies().percentileMillis(50)));
        row(out, "latency p90 (ms)", fmt("%,.0f", r -> r.latencies().percentileMillis(90)));
        row(out, "latency p99 (ms)", fmt("%,.0f", r -> r.latencies().percentileMillis(99)));
        row(out, "client heap growth (MB)", fmt("%,.0f", r -> r.clientPeakHeap() / 1048576.0));
        row(out, "api peak RSS (MB)", fmt("%,.0f", r -> r.apiPeakRss() / 1048576.0));
        row(out, "api CPU (s)", fmt("%.1f", Result::apiCpuSeconds));
        row(out, "consumer peak RSS (MB)", fmt("%,.0f", r -> r.consumerPeakRss() / 1048576.0));
        row(out, "consumer CPU (s)", fmt("%.1f", Result::consumerCpuSeconds));
        System.out.println(out);
    }

    private List<String> fmt(String format, java.util.function.ToDoubleFunction<Result> value) {
        return results.stream().map(r -> {
            double v = value.applyAsDouble(r);
            return format.contains("d") ? String.format(Locale.ROOT, format, (long) v)
                    : String.format(Locale.ROOT, format, v);
        }).toList();
    }

    private static void row(StringBuilder out, String label, List<String> values) {
        out.append(String.format(Locale.ROOT, "%-26s", label));
        for (String value : values) {
            out.append(String.format(Locale.ROOT, "%18s", value));
        }
        out.append('\n');
    }
}
