// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Content;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The state of one run attempt while it executes: the queue in front of every processor, the
 * content its items share, and how much of the run's memory cap that content takes. Lives on one
 * thread.
 */
final class Run {

    final UUID runId;
    final int attempt;
    /** The run's memory cap, in bytes, and how much of it the content alive now takes. */
    final long cap;
    long used;
    final Clock clock;
    final RecordSink sink;
    final Map<String, ArrayDeque<RunItem>> queues = new HashMap<>();
    /** Every content the run's live items hold, keyed by identity. */
    final Map<Content, ContentRef> refs = new IdentityHashMap<>();
    long records;

    Run(UUID runId, int attempt, long cap, Clock clock, RecordSink sink) {
        this.runId = runId;
        this.attempt = attempt;
        this.cap = cap;
        this.clock = clock;
        this.sink = sink;
    }

    ArrayDeque<RunItem> queue(String nodeId) {
        return queues.computeIfAbsent(nodeId, k -> new ArrayDeque<>());
    }

    /** Counts {@code bytes} against the cap before they are allocated. */
    void claim(long bytes) {
        if (bytes > cap - used) {
            throw new OverCap(bytes);
        }
        used += bytes;
    }

    void free(long bytes) {
        used -= bytes;
    }

    /** Content would go over the run's memory cap. */
    static final class OverCap extends RuntimeException {
        OverCap(long bytes) {
            super(bytes + " more bytes do not fit in the run's memory cap");
        }
    }

    void emit(FlowRecord record) {
        sink.accept(record);
        records++;
    }

    /** Ends the run's hold on every content, whatever state it stopped in. */
    void releaseAll() {
        queues.clear();
        for (ContentRef ref : refs.values()) {
            ref.close();
        }
        refs.clear();
    }
}
