// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples another process's resident memory and CPU from {@code /proc}, for the api and the
 * consumer while a benchmark runs.
 *
 * <p>Resident set rather than Java heap on purpose: the question a binary ingest path raises is
 * whether request bodies inflate the server's footprint, and that includes what lives outside the
 * heap. It is a peak of samples, so a spike between two ticks is missed; the sampling interval is
 * short enough that a multi-second ingest cannot hide in it.
 */
final class ProcessSampler implements AutoCloseable {

    private final long pid;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong peakRssBytes = new AtomicLong();
    private final long cpuTicksAtStart;
    private volatile long cpuTicksAtEnd;

    private ProcessSampler(long pid) {
        this.pid = pid;
        this.cpuTicksAtStart = cpuTicks();
        this.cpuTicksAtEnd = cpuTicksAtStart;
        this.thread = Thread.ofVirtual().start(this::sampleLoop);
    }

    /** A sampler for the process, or null when there is no such pid or no procfs to read. */
    static ProcessSampler of(long pid) {
        if (pid <= 0 || !Files.isReadable(Path.of("/proc/" + pid + "/statm"))) {
            return null;
        }
        return new ProcessSampler(pid);
    }

    /** The pid listening on a TCP port, or -1. Saves the caller hunting for it by hand. */
    static long pidListeningOn(int port) {
        try {
            Process process = new ProcessBuilder("bash", "-c",
                    "ss -ltnpH 'sport = :" + port + "' | grep -o 'pid=[0-9]*' | head -n1 | cut -d= -f2")
                    .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return out.isEmpty() ? -1 : Long.parseLong(out);
        } catch (Exception e) {
            return -1;
        }
    }

    private void sampleLoop() {
        while (running.get()) {
            long rss = rssBytes();
            peakRssBytes.accumulateAndGet(rss, Math::max);
            cpuTicksAtEnd = cpuTicks();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private long rssBytes() {
        try {
            // statm field 2 is resident pages.
            String[] fields = Files.readString(Path.of("/proc/" + pid + "/statm")).trim().split("\\s+");
            return Long.parseLong(fields[1]) * 4096L;
        } catch (Exception e) {
            return 0;
        }
    }

    private long cpuTicks() {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            // Fields after the (comm) parenthesis: utime is 14th overall, stime 15th.
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (Exception e) {
            return 0;
        }
    }

    long peakRssBytes() {
        return peakRssBytes.get();
    }

    /** CPU seconds this process burned between construction and now. */
    double cpuSeconds() {
        long hz = 100; // USER_HZ is 100 on every Linux this runs on
        return (cpuTicksAtEnd - cpuTicksAtStart) / (double) hz;
    }

    @Override
    public void close() {
        running.set(false);
        thread.interrupt();
    }
}
