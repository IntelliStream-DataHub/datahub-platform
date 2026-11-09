// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Content;
import ai.intellistream.datahub.flow.api.Item;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One version of an item. Every change makes a new one with the same id, so the engine can tell a
 * processor still holding an old version from one holding the current.
 *
 * @param ref     the content, or null for none
 * @param parents for an item made from another (a fork or a copy), that one
 */
record RunItem(UUID id, Map<String, String> attributes, ContentRef ref, List<UUID> parents)
        implements Item {

    RunItem {
        attributes = Map.copyOf(attributes);
        parents = List.copyOf(parents);
    }

    @Override
    public Content content() {
        return ref == null ? null : ref.content;
    }

    RunItem withAttributes(Map<String, String> changed) {
        return new RunItem(id, changed, ref, parents);
    }

    RunItem withContent(ContentRef content) {
        return new RunItem(id, attributes, content, parents);
    }

    /** Records compare identity: two versions of one item are different objects. */
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "Item[" + id + "]";
    }
}
