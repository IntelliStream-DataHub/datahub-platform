// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

/**
 * What an item carries. Content is never changed once made: a processor that changes it makes new
 * content and writes that to the item. That is what makes copying an item cheap (copies share their
 * content) and what lets the engine record a hash of the content before and after each step.
 *
 * <p>The engine owns every content's memory. A processor makes content through its session, hands
 * it to an item, and never releases it.
 */
public sealed interface Content permits TimeseriesContent, EventsContent {

    ContentKind kind();

    /** Bytes held, as counted against the run's memory cap. */
    long size();

    /** How much data it holds: points of a series, or events. What its records say it carries. */
    int rowCount();
}
