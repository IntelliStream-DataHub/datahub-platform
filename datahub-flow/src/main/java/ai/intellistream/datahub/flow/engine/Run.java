// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Content;
import org.apache.arrow.memory.BufferAllocator;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The state of one run attempt while it executes: the queue in front of every processor, the
 * content its items share, and the allocator their memory comes from. Lives on one thread.
 */
final class Run {

    final UUID runId;
    final int attempt;
    final BufferAllocator allocator;
    final Clock clock;
    final RecordSink sink;
    final Map<String, ArrayDeque<RunItem>> queues = new HashMap<>();
    /** Every content the run's live items hold, keyed by identity. */
    final Map<Content, ContentRef> refs = new IdentityHashMap<>();
    long records;

    Run(UUID runId, int attempt, BufferAllocator allocator, Clock clock, RecordSink sink) {
        this.runId = runId;
        this.attempt = attempt;
        this.allocator = allocator;
        this.clock = clock;
        this.sink = sink;
    }

    ArrayDeque<RunItem> queue(String nodeId) {
        return queues.computeIfAbsent(nodeId, k -> new ArrayDeque<>());
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
