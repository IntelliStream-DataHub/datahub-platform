// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A durable, file-backed spool of items that failed to ingest, kept so they can be retried once the
 * server is reachable. Implemented as a <b>segmented newline-delimited-JSON log</b> that stays
 * memory-safe even near a multi-gigabyte cap, with compression deferred to segment rollover:
 *
 * <ul>
 *   <li>The <b>active segment</b> is plain, append-only NDJSON ({@code .ndjson}). Appends are line
 *       writes, so an unclean shutdown leaves at most a torn last line, which the reader skips.</li>
 *   <li>At <b>rollover</b> ({@code rolloverBytes}, ~50 MiB) the active segment is gzip-sealed to a
 *       {@code .ndjson.gz} (temp file + atomic move, then the plain file is deleted) and a fresh active
 *       segment is started. Compressing whole segments gives a real ratio, unlike per-append members.</li>
 *   <li><b>drain</b> streams the oldest segment first in fixed-size record chunks, sending each chunk
 *       and deleting the segment once fully sent. Memory is bounded by the chunk, not the file.</li>
 *   <li><b>time retention</b> ({@code retention}, nullable): whole segments past the window are dropped
 *       and expired records are skipped on drain. Age uses each record's own timestamp.</li>
 *   <li><b>size cap</b> ({@code maxBytes}, nullable): bounds total on-disk size; when exceeded the oldest
 *       segment is deleted (O(1), no recompression).</li>
 * </ul>
 *
 * <p>Thread-safe via coarse synchronization. Items must round-trip through the supplied Jackson mapper
 * as a single JSON line (Jackson escapes embedded newlines, so this holds for any value).
 *
 * @param <T> the spooled item type (e.g. a datapoint line or an event)
 */
public final class DurableSpool<T> {

    /** Sends one chunk of records; returns {@code true} if the server accepted them (so they can be dropped). */
    @FunctionalInterface
    public interface ChunkSender<T> {
        boolean send(List<T> chunk);
    }

    private static final int DRAIN_CHUNK_RECORDS = 5_000;
    private static final long DEFAULT_ROLLOVER_BYTES = 50L * 1024 * 1024; // 50 MiB of plain NDJSON
    private static final long MIN_ROLLOVER_BYTES = 64L * 1024;
    private static final String ACTIVE_SUFFIX = ".ndjson";
    private static final String SEALED_SUFFIX = ".ndjson.gz";
    private static final String TEMP_SUFFIX = ".ndjson.gz.tmp";

    private final Path dir;
    private final Duration retention; // nullable: no time bound
    private final Long maxBytes;      // nullable: no size bound (total on-disk)
    private final long rolloverBytes;
    private final JsonMapper mapper;
    private final JavaType elementType;
    private final ToLongFunction<T> timestampMillis;

    private final List<Segment> segments = new ArrayList<>(); // ordered by sequence (oldest first)
    private long nextSeq;

    /** One on-disk segment file plus the lightweight stats we keep in memory for retention. */
    private static final class Segment {
        Path path;
        boolean compressed;
        final long seq;
        long maxTs = Long.MIN_VALUE;
        long records;
        long bytes;

        Segment(Path path, boolean compressed, long seq) {
            this.path = path;
            this.compressed = compressed;
            this.seq = seq;
        }
    }

    public DurableSpool(Path dir, Duration retention, Long maxBytes, JsonMapper mapper,
                        Class<T> elementType, ToLongFunction<T> timestampMillis) {
        if (retention == null && maxBytes == null) {
            throw new IllegalArgumentException("a spool needs at least one of retention or maxBytes");
        }
        this.dir = dir;
        this.retention = retention;
        this.maxBytes = maxBytes;
        this.rolloverBytes = maxBytes != null
                ? Math.max(MIN_ROLLOVER_BYTES, Math.min(DEFAULT_ROLLOVER_BYTES, maxBytes / 4))
                : DEFAULT_ROLLOVER_BYTES;
        this.mapper = mapper;
        this.timestampMillis = timestampMillis;
        this.elementType = mapper.getTypeFactory().constructType(elementType);
        recover();
    }

    /** Total records currently spooled across all segments. */
    public synchronized long size() {
        long total = 0;
        for (Segment s : segments) {
            total += s.records;
        }
        return total;
    }

    /** Append records to the active (plain) segment, sealing it at rollover, then enforce the bounds. */
    public synchronized void append(List<T> items, long nowMillis) {
        if (items.isEmpty()) {
            return;
        }
        prune(nowMillis);
        try {
            Files.createDirectories(dir);
            Segment active = activeForWrite();
            appendLines(active.path, items);
            for (T item : items) {
                active.maxTs = Math.max(active.maxTs, timestamp(item));
            }
            active.records += items.size();
            active.bytes = Files.size(active.path);
            if (active.bytes >= rolloverBytes) {
                seal(active);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not append to spool in " + dir, e);
        }
        prune(nowMillis); // a large append may push us over the byte cap
    }

    /**
     * Flush the spool: stream the oldest segment first, sending records in chunks via {@code sender}.
     * A segment that is fully accepted is deleted; the first chunk the sender rejects stops the drain
     * (the server is presumed down) and leaves the rest on disk. Returns {@code true} if everything
     * was drained.
     */
    public synchronized boolean drain(ChunkSender<T> sender, long nowMillis) {
        prune(nowMillis);
        long cutoff = retention != null ? nowMillis - retention.toMillis() : Long.MIN_VALUE;
        for (Segment segment : new ArrayList<>(segments)) {
            if (!drainSegment(segment, sender, cutoff)) {
                return false; // server down: keep this and the remaining segments
            }
            delete(segment);
        }
        return true;
    }

    /** Remove every segment (called after the caller no longer needs the buffered data). */
    public synchronized void clear() {
        for (Segment segment : new ArrayList<>(segments)) {
            delete(segment);
        }
    }

    // --- draining ------------------------------------------------------------------------------

    private boolean drainSegment(Segment segment, ChunkSender<T> sender, long cutoff) {
        List<T> chunk = new ArrayList<>(DRAIN_CHUNK_RECORDS);
        boolean[] keepGoing = {true};
        forEachRecord(segment, record -> {
            if (timestamp(record) < cutoff) {
                return true; // expired: drop without sending
            }
            chunk.add(record);
            if (chunk.size() >= DRAIN_CHUNK_RECORDS) {
                if (!sender.send(new ArrayList<>(chunk))) {
                    keepGoing[0] = false;
                    return false;
                }
                chunk.clear();
            }
            return true;
        });
        if (!keepGoing[0]) {
            return false;
        }
        return chunk.isEmpty() || sender.send(chunk);
    }

    // --- pruning -------------------------------------------------------------------------------

    private void prune(long nowMillis) {
        if (retention != null) {
            long cutoff = nowMillis - retention.toMillis();
            while (!segments.isEmpty() && segments.get(0).maxTs < cutoff) {
                delete(segments.get(0));
            }
        }
        if (maxBytes != null) {
            while (!segments.isEmpty() && totalBytes() > maxBytes) {
                delete(segments.get(0));
            }
        }
    }

    private long totalBytes() {
        long total = 0;
        for (Segment s : segments) {
            total += s.bytes;
        }
        return total;
    }

    // --- segment lifecycle ---------------------------------------------------------------------

    private Segment activeForWrite() {
        if (!segments.isEmpty()) {
            Segment last = segments.get(segments.size() - 1);
            if (!last.compressed) {
                return last; // still appending to the open plain segment
            }
        }
        Segment fresh = new Segment(dir.resolve(name(nextSeq, ACTIVE_SUFFIX)), false, nextSeq);
        nextSeq++;
        segments.add(fresh);
        return fresh;
    }

    /** Compress a full plain segment to {@code .ndjson.gz} (temp + atomic move), then drop the plain file. */
    private void seal(Segment segment) throws IOException {
        Path sealed = dir.resolve(name(segment.seq, SEALED_SUFFIX));
        Path tmp = dir.resolve(name(segment.seq, TEMP_SUFFIX));
        try (InputStream in = Files.newInputStream(segment.path);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(
                     tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            in.transferTo(out);
        }
        Files.move(tmp, sealed, StandardCopyOption.REPLACE_EXISTING);
        Path plain = segment.path;
        segment.path = sealed;
        segment.compressed = true;
        segment.bytes = Files.size(sealed);
        Files.deleteIfExists(plain);
    }

    private void delete(Segment segment) {
        try {
            Files.deleteIfExists(segment.path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove spool segment " + segment.path, e);
        }
        segments.remove(segment);
    }

    // --- file I/O ------------------------------------------------------------------------------

    private void appendLines(Path path, List<T> items) throws IOException {
        try (OutputStream os = new BufferedOutputStream(
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            for (T item : items) {
                os.write(mapper.writeValueAsString(item).getBytes(StandardCharsets.UTF_8));
                os.write('\n');
            }
        }
    }

    /** Stream parsed records from a segment, tolerating a torn trailing line and bad lines. */
    private void forEachRecord(Segment segment, Predicate<T> consumer) {
        try (InputStream base = Files.newInputStream(segment.path);
             InputStream in = segment.compressed ? new GZIPInputStream(base) : base;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            while (true) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException | RuntimeException e) {
                    return; // truncated tail after an unclean shutdown: stop here
                }
                if (line == null) {
                    return;
                }
                if (line.isBlank()) {
                    continue;
                }
                T record;
                try {
                    record = mapper.readValue(line, elementType);
                } catch (RuntimeException e) {
                    continue; // skip a corrupt line rather than abort the whole segment
                }
                if (!consumer.test(record)) {
                    return;
                }
            }
        } catch (IOException e) {
            System.err.println("DataHub SDK: ignoring unreadable spool segment " + segment.path + ": " + e.getMessage());
        }
    }

    /** Rebuild the in-memory segment index from disk (on construction, e.g. after a restart). */
    private void recover() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        TreeMap<Long, Path> sealed = new TreeMap<>();
        TreeMap<Long, Path> plain = new TreeMap<>();
        List<Path> temps = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(TEMP_SUFFIX)) {
                    temps.add(p); // leftover from a crash mid-seal
                } else if (n.endsWith(SEALED_SUFFIX)) {
                    putSeq(sealed, p, SEALED_SUFFIX);
                } else if (n.endsWith(ACTIVE_SUFFIX)) {
                    putSeq(plain, p, ACTIVE_SUFFIX);
                }
            });
        } catch (IOException e) {
            System.err.println("DataHub SDK: could not list spool dir " + dir + ": " + e.getMessage());
            return;
        }
        deleteQuietly(temps);

        TreeMap<Long, Segment> bySeq = new TreeMap<>();
        sealed.forEach((seq, path) -> bySeq.put(seq, new Segment(path, true, seq)));
        plain.forEach((seq, path) -> {
            if (bySeq.containsKey(seq)) {
                deleteQuietly(List.of(path)); // sealed wins a crash-interrupted seal; drop the stale plain file
            } else {
                bySeq.put(seq, new Segment(path, false, seq));
            }
        });

        for (Map.Entry<Long, Segment> entry : bySeq.entrySet()) {
            Segment segment = entry.getValue();
            scan(segment);
            segments.add(segment);
            nextSeq = Math.max(nextSeq, segment.seq + 1);
        }
    }

    private void scan(Segment segment) {
        long[] stats = {Long.MIN_VALUE, 0}; // maxTs, records
        forEachRecord(segment, record -> {
            stats[0] = Math.max(stats[0], timestamp(record));
            stats[1]++;
            return true;
        });
        segment.maxTs = stats[0];
        segment.records = stats[1];
        try {
            segment.bytes = Files.size(segment.path);
        } catch (IOException e) {
            segment.bytes = 0;
        }
    }

    private static void putSeq(Map<Long, Path> target, Path path, String suffix) {
        String name = path.getFileName().toString();
        try {
            target.put(Long.parseLong(name.substring(0, name.length() - suffix.length())), path);
        } catch (RuntimeException ignored) {
            // not one of ours; skip
        }
    }

    private static void deleteQuietly(List<Path> paths) {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static String name(long seq, String suffix) {
        return String.format("%019d%s", seq, suffix);
    }

    private long timestamp(T item) {
        try {
            return timestampMillis.applyAsLong(item);
        } catch (RuntimeException e) {
            return Long.MIN_VALUE; // un-timestamped: treat as oldest (expires/evicts first)
        }
    }
}
