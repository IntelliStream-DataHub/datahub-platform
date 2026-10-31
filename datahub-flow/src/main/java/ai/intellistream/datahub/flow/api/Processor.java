// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import java.util.List;

/**
 * One step in a flow: it reads, transforms or writes the items that pass through it, and sends each
 * on along one of its named outcomes (relationships).
 *
 * <p>Every processor implements this interface, built-in ones now and others later. One instance
 * serves every flow and every run that uses it, concurrently, so an implementation keeps no state of
 * its own: what it is configured with arrives in the {@link ProcessContext}, and the items it works
 * on in the {@link ProcessSession}.
 *
 * <p>A processor never records what it did. The engine derives the record of every run from the
 * session calls, so a new processor is recorded correctly without doing anything.
 */
public interface Processor {

    /** Name, version, properties and relationships, as the catalog publishes them. */
    ProcessorDescriptor describe();

    /**
     * Problems with a combination of property values that no single property can see, such as
     * "set {@code min}, {@code max} or both". Called when a flow is saved, with the values as
     * written: a value that is a {@code ${…}} reference is set, but not yet resolved.
     */
    default List<String> validate(PropertyValues properties) {
        return List.of();
    }

    /**
     * Takes items from the session, produces or changes items, and transfers each to a
     * relationship. One call is one small transaction: if it throws, everything it did through the
     * session is discarded and its items go back on its queue.
     *
     * <p>A source (a processor nothing is connected into) is called once per run; any other
     * processor is called while its queue holds items, and must take at least one each time.
     */
    void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException;
}
