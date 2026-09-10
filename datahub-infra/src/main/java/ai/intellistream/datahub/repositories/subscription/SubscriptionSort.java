// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.models.DataSort;

import java.util.Map;

/**
 * The order a subscription query runs in: one sortable property plus the {@code id} tie-breaker.
 *
 * <p>The subscription counterpart of {@code NodeSort}, and deliberately a separate type rather than
 * a reuse of it: the two whitelists name different columns — a subscription has no {@code source},
 * {@code description} or {@code dataSet} — and a shared whitelist would let a request sort a
 * subscription query by a column the table does not have. It is a whitelist rather than an open
 * field name for the same reason as there: a column name reaching a query from a request body is an
 * injection point no amount of parameter binding elsewhere makes up for.
 *
 * <p>Every sortable column here is {@code NOT NULL} in the schema, so unlike the node sort there is
 * no null block to place in the {@code ORDER BY} or to straddle in the keyset predicate.
 */
public record SubscriptionSort(String property, String attribute, boolean descending) {

    /** Newest created first — what {@code /subscriptions/list} returned before it could be sorted. */
    public static final SubscriptionSort DEFAULT = new SubscriptionSort("createdTime", "dateCreated", true);

    /** Sortable properties, mapped to the entity attribute behind each. */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "externalId", "externalId",
            "name", "name",
            "createdTime", "dateCreated",
            "lastUpdatedTime", "lastUpdated");

    /**
     * Resolve a request's sort. An unrecognised property falls back to the default rather than
     * failing, matching {@code NodeSort.resolve} — and replacing the previous behaviour, where the
     * property went straight into {@code Sort.by(...)} and an unknown one surfaced as a 500 from
     * Spring Data rather than as the default order.
     */
    public static SubscriptionSort resolve(DataSort sort) {
        if (sort == null || sort.getProperty() == null || sort.getProperty().isEmpty()) {
            return DEFAULT;
        }
        for (String property : sort.getProperty()) {
            String attribute = SORTABLE.get(property == null ? null : property.trim());
            if (attribute != null) {
                // Anything that is not an explicit "desc" is ascending, so a malformed order
                // degrades predictably instead of silently reversing the results.
                return new SubscriptionSort(property.trim(), attribute, "desc".equalsIgnoreCase(sort.getOrder()));
            }
        }
        return DEFAULT;
    }

    /**
     * Whether a cursor's boundary value can be read as this column's type. A cursor is opaque but
     * not signed, so anything can arrive in one; an unparseable boundary must be a 400 rather than
     * a {@code NumberFormatException} escaping the query builder as a 500.
     *
     * <p>A null boundary is rejected here where {@code NodeSort} accepts one. There it addresses
     * the null block of a nullable sort column; no sortable column on this table is nullable, so a
     * cursor claiming to sit in a block that does not exist was not one this API minted.
     */
    public boolean canReadBoundary(String value) {
        if (value == null) {
            return false;
        }
        return switch (attribute) {
            case "id", "dateCreated", "lastUpdated" -> isLong(value);
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
}
