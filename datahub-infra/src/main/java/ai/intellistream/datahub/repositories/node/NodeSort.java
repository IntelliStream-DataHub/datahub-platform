// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.models.DataSort;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The order a node query runs in: one sortable property plus the {@code id} tie-breaker.
 *
 * <p>One column rather than several because the cursor encodes the position it stopped at, and a
 * multi-column position is a tuple comparison with a direction per column — worth having, not worth
 * guessing at. {@code id} is always appended, which is what makes the order <em>total</em>: a sort
 * column alone is not a position unless it is unique, and a page boundary falling inside a run of
 * equal values repeats or drops exactly those rows.
 *
 * <p>The whitelist is not restricted to indexed columns. Sorting by an unindexed one sorts the
 * matched set rather than reading it in order, which is a cost rather than a correctness problem;
 * indexes get added where the sorts turn out to be. It <em>is</em> a whitelist rather than an open
 * field name, because a column name reaching a query from a request body is an injection point no
 * amount of parameter binding elsewhere makes up for.
 */
public record NodeSort(String property, String attribute, boolean descending) {

    /** Newest created first — what the three node filters returned before they could be sorted. */
    public static final NodeSort DEFAULT = new NodeSort("createdTime", "dateCreated", true);

    /** Sortable properties, mapped to the entity attribute behind each. */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "externalId", "externalId",
            "name", "name",
            "source", "source",
            "description", "description",
            "createdTime", "dateCreated",
            "lastUpdatedTime", "lastUpdated",
            "dataSetId", "dataSet");

    /**
     * Attributes that may be null, and therefore need the null block handled explicitly in both the
     * ORDER BY and the keyset predicate.
     *
     * <p>Most of them, because every node type shares one table: a column only some types use has
     * to be nullable for the rest. Only {@code id}, {@code name} and {@code externalId} are
     * declared NOT NULL. {@code dateCreated} and {@code lastUpdated} are populated on every write
     * by Hibernate and are nullable only because the schema never said otherwise — worth making
     * NOT NULL, which is a backfill and a migration rather than something to assume here.
     */
    private static final Set<String> NULLABLE = Set.of(
            "source", "description", "dateCreated", "lastUpdated", "dataSet");

    /**
     * Resolve a request's sort. An unrecognised property falls back to the default rather than
     * failing, matching how the rest of the filter treats what it does not recognise — so a
     * misspelling returns the default order, which is visibly not what was asked for.
     */
    public static NodeSort resolve(DataSort sort) {
        if (sort == null || sort.getProperty() == null || sort.getProperty().isEmpty()) {
            return DEFAULT;
        }
        for (String property : sort.getProperty()) {
            String attribute = SORTABLE.get(property);
            if (attribute != null) {
                // Anything that is not an explicit "desc" is ascending, so a malformed order
                // degrades predictably instead of silently reversing the results.
                return new NodeSort(property, attribute, "desc".equalsIgnoreCase(sort.getOrder()));
            }
        }
        return DEFAULT;
    }

    /** Whether this sort's column can be null, and so needs the null block handled. */
    public boolean nullable() {
        return NULLABLE.contains(attribute);
    }

    /**
     * Where nulls sit in this order: last when ascending, first when descending.
     *
     * <p>Chosen to match how a Postgres btree stores them by default, so a plain
     * {@code CREATE INDEX} on the column can serve the ordering in either direction without an
     * explicit {@code NULLS} clause in the index definition.
     */
    public boolean nullsLast() {
        return !descending;
    }

    /** The path segment for the joined data set id, which is a relation rather than a column. */
    public boolean isRelation() {
        return "dataSet".equals(attribute);
    }

    /**
     * Whether a cursor's boundary value can be read as this column's type.
     *
     * <p>A cursor is opaque but not signed, so anything can arrive in one. Numeric and temporal
     * columns parse their boundary, and an unparseable one used to propagate a
     * {@code NumberFormatException} out of the query builder — a 500 from a value the caller
     * supplied. A null value is always usable: it is how the null block is addressed.
     */
    public boolean canReadBoundary(String value) {
        if (value == null) {
            return true;
        }
        return switch (attribute) {
            case "id", "dataSet", "dateCreated", "lastUpdated" -> isLong(value);
            default -> true;
        };
    }

    private static boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String normaliseProperty(String property) {
        return property == null ? null : property.trim();
    }

    static boolean isSortable(String property) {
        return SORTABLE.containsKey(normaliseProperty(property));
    }

    static Locale locale() {
        return Locale.ROOT;
    }
}
