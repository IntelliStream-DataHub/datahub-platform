// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

/**
 * The page-size contract shared by every filter endpoint.
 *
 * <p>Here rather than repeated per retriever because they had drifted: two defaulted to 100 and two
 * to 1000, so which page size a caller got depended on which entity they were asking about rather
 * than on anything they had said. One constant is harder to drift than four literals.
 */
public final class FilterDefaults {

    /** Rows returned when the caller does not say. */
    public static final int DEFAULT_LIMIT = 1000;

    /** The most any single call may ask for. */
    public static final int MAX_LIMIT = 10000;

    private FilterDefaults() {
    }
}
