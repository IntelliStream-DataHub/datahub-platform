// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

/**
 * What an item's content holds. The kind travels with the content, so a processor that gets content
 * it cannot handle fails clearly, and checking connected processors against each other can be
 * switched on later without changing any processor.
 *
 * <p>The first version's processors produce {@link #TIMESERIES} and {@link #EVENTS}; the other kinds
 * are part of the model, and their content classes arrive with the processors that produce them.
 */
public enum ContentKind {
    /** One series over one window: an Arrow batch of {@code (timestamp, value)}. */
    TIMESERIES("timeseries"),
    /** A batch of DataHub event transitions. */
    EVENTS("events"),
    /** Rows with an Arrow schema. */
    RECORDS("records"),
    /** A JSON document. */
    JSON("json"),
    /** Anything else, with a media type. */
    BYTES("bytes");

    private final String wireName;

    ContentKind(String wireName) {
        this.wireName = wireName;
    }

    /** The name in records and the catalog, e.g. {@code timeseries}. */
    public String wireName() {
        return wireName;
    }
}
