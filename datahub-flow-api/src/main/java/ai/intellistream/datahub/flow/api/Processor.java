// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

/**
 * One step in a flow: it reads, transforms or writes the items that pass through it, and sends each
 * on along one of its named outcomes (relationships).
 *
 * <p>Every processor implements this interface, built-in ones now and others later. So far it says
 * only what a processor is, for the catalog; what it does with items arrives with the engine,
 * together with the context and session it is handed.
 */
public interface Processor {

    /** Name, version, description and relationships, as the catalog publishes them. */
    ProcessorDescriptor describe();
}
