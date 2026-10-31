// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.ContentKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One thing a processor did to an item, derived by the engine from the session call that did it.
 * The same for any kind of data; what is specific to DataHub's data is in the transit URI.
 *
 * @param time              when the invocation that did it was committed
 * @param processorId       the processor's id in the definition
 * @param processorType     its catalog name
 * @param parentItemIds     for a fork or a copy, the item it came from
 * @param relationship      for a route or a copy, the relationship it left on; for a drop or a
 *                          send that ended the item, the terminated one it ended on
 * @param contentKind       the item's content kind at that point, or null for an item with none
 * @param size              how much data its content holds ({@link ai.intellistream.datahub.flow.api.Content#rowCount}),
 *                          0 for none
 * @param hash              xxHash of its content's canonical form ({@link ContentHash}), 0 for none
 * @param changedAttributes for a create, fork or attribute change: the attributes set, a removed
 *                          one with an empty value
 * @param transitUri        for a receive or a send: where the content came from or went
 * @param durationMillis    how long the invocation that did it took
 */
public record FlowRecord(Instant time, UUID runId, int attempt, String processorId,
                         String processorType, Action action, UUID itemId,
                         List<UUID> parentItemIds, String relationship, ContentKind contentKind,
                         long size, long hash, Map<String, String> changedAttributes,
                         String transitUri, long durationMillis) {

    public enum Action {
        /** A new item made inside the flow. A source's item for what it read is a receive instead. */
        CREATE,
        /** A new item derived from another: a split, or one of several outputs from one input. */
        FORK,
        /** The item's content was read from outside the flow; for a new item, also its creation. */
        RECEIVE,
        /**
         * The item's content was written outside the flow. With a relationship, the item ended
         * there, on that terminated relationship, and has no route or drop record of its own.
         */
        SEND,
        ATTRIBUTES_MODIFIED,
        CONTENT_MODIFIED,
        /** Sent on along a relationship. */
        ROUTE,
        /** A copy made because a relationship feeds several processors. */
        CLONE,
        /**
         * The item ended: with a relationship, it reached a terminated one (the end of the flow);
         * without, the processor removed it.
         */
        DROP
    }
}
