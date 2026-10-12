// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

import java.util.Map;
import java.util.UUID;

/**
 * One piece of data moving through a flow: attributes (a small string map) plus content. Usually a
 * batch — for a timeseries, one series over one window — not a single value.
 *
 * <p>An item is a value: the session's calls that change it return the changed item, and the one
 * passed in is no longer current. Its id stays the same through those changes; a copy or a split
 * gets a new id, with the original recorded as its parent.
 */
public interface Item {

    UUID id();

    /** Unmodifiable. */
    Map<String, String> attributes();

    default String attribute(String name) {
        return attributes().get(name);
    }

    /** The content, or null for an item that has none yet. */
    Content content();

    /**
     * The content as {@code type}, or a {@link ProcessException} naming what it is instead — the way
     * a processor connected to the wrong kind of input fails.
     */
    default <T extends Content> T content(Class<T> type) {
        Content content = content();
        if (!type.isInstance(content)) {
            throw new ProcessException("Expected " + type.getSimpleName() + " content, got "
                    + (content == null ? "none" : content.kind().wireName()));
        }
        return type.cast(content);
    }
}
