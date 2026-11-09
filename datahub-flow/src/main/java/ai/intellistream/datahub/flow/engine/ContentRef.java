// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Content;

/**
 * The engine's hold on one content: what releases its memory, and how many live items share it.
 *
 * <p>Content is shared, never copied: a branch's copies all point at their original's content, and
 * a processor may give the content it received to a new item. {@code holders} counts the items that
 * refer to the content and are still live — waiting on a queue, or held by a processor in the
 * middle of an invocation. When the last one ends, its size goes back to the run's memory cap.
 * Counts change only when an invocation commits, so a rollback has nothing to undo.
 */
final class ContentRef {

    final Content content;
    private final AutoCloseable memory;
    int holders;
    private Long hash;
    private boolean closed;

    /**
     * @param memory gives the content's size back to the run's memory cap
     */
    ContentRef(Content content, AutoCloseable memory) {
        this.content = content;
        this.memory = memory;
    }

    /** {@link ContentHash} of the content, computed once: content never changes. */
    long hash() {
        if (hash == null) {
            hash = ContentHash.of(content);
        }
        return hash;
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            memory.close();
        } catch (Exception e) {
            throw new IllegalStateException("Could not release a content's memory", e);
        }
    }
}
