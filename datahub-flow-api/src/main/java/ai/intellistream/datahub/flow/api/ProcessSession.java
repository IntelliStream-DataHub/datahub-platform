// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

import java.util.List;
import java.util.Map;

/**
 * A processor's only way to touch items. Every call is something the engine records, and everything
 * done through one session is one transaction: if {@link Processor#onTrigger} throws, all of it is
 * discarded and the items it took go back on its queue.
 *
 * <p>Every item a processor takes or creates must, by the time {@code onTrigger} returns, have been
 * transferred to a relationship or removed. An item left over fails the invocation.
 *
 * <p>The methods that change an item return the changed item; pass that one to later calls.
 */
public interface ProcessSession {

    /** The next item on this processor's queue, or null when it is empty. */
    Item get();

    /** Up to {@code max} items from this processor's queue, oldest first. */
    List<Item> get(int max);

    /** A new item with no attributes and no content, as a source makes for what it read. */
    Item create();

    /**
     * A new item derived from {@code parent} — a split, or one of several outputs made from one
     * input. It starts with the parent's attributes and no content, and the parent is recorded as
     * its parent. The parent itself still has to be transferred or removed.
     */
    Item create(Item parent);

    Item putAttribute(Item item, String key, String value);

    Item putAllAttributes(Item item, Map<String, String> attributes);

    Item removeAttribute(Item item, String key);

    /** Gives {@code item} new content, replacing what it had. */
    Item write(Item item, Content content);

    /**
     * An empty timeseries batch to fill, its memory taken from the run's allocator and counted
     * against the run's cap.
     */
    TimeseriesContent.Builder newTimeseries(int expectedRows);

    /**
     * Records that {@code item}'s content was read from outside the flow, at {@code transitUri}
     * (e.g. {@code datahub://timeseries/ts_raw?from=…&to=…}). Never put a credential in the URI.
     */
    void receive(Item item, String transitUri);

    /** Records that {@code item}'s content was written outside the flow, to {@code transitUri}. */
    void send(Item item, String transitUri);

    /** Sends {@code item} on along {@code relationship}. */
    void transfer(Item item, String relationship);

    /** Ends {@code item} here. */
    void remove(Item item);
}
