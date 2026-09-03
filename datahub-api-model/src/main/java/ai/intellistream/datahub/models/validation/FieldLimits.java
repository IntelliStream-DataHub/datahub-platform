// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

/**
 * Size ceilings for the free-form fields of the wire contract, in one place so the bean-validation
 * annotations, the hand-written update validators and the OpenAPI schema documentation cannot drift
 * apart.
 *
 * <p>These bound what a single entity can carry. Without them a caller can use {@code description}
 * or {@code metadata} as file storage: both were unbounded at every layer, and events are not even
 * subject to the incidental Postgres ceilings that nodes inherit from their indexes.
 */
public final class FieldLimits {

    private FieldLimits() {
    }

    /** Long enough for a substantial prose description, short enough to be useless as a file. */
    public static final int DESCRIPTION_MAX = 10_000;

    /** Metadata is a tag store, not a document store. */
    public static final int METADATA_MAX_ENTRIES = 256;

    /** Well under the {@code node_metadata.key varchar(1024)} column. */
    public static final int METADATA_KEY_MAX = 128;

    /** Keeps key+value under the ~2.7 KB {@code (node_id, key, value)} btree index-tuple ceiling. */
    public static final int METADATA_VALUE_MAX = 1_024;

    public static final int RELATED_RESOURCES_MAX = 100;

    public static final int LABELS_MAX = 64;

    public static final int LABEL_LENGTH_MAX = 512;

    /** A detailed polygon fits; a payload does not. */
    public static final int GEOJSON_MAX_CHARS = 65_536;

    /** Numeric strings and status codes fit. */
    public static final int DATAPOINT_VALUE_MAX = 64;

    /** Items in one {@code DataWrapper}. Matches the Java SDK's default ingest batch size. */
    public static final int BATCH_ITEMS_MAX = 10_000;

    /** Datapoints in one collection, for numeric series. */
    public static final int DATAPOINTS_PER_COLLECTION_MAX = 100_000;

    /**
     * Datapoints in one collection for TEXT/MIXED series. Tighter than the numeric cap because a
     * text batch is the one shape that can approach Pulsar's 5 MB per-message ceiling: this keeps a
     * worst-case collection at roughly 640 KB of values. Enforced in the service, once the series'
     * value type is known — bean validation runs before the series is resolved.
     */
    public static final int TEXT_DATAPOINTS_PER_COLLECTION_MAX = 10_000;
}
