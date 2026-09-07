// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.subscription.SubscriptionFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns {@link SubscriptionFilter} into Criteria predicates — the {@code /subscriptions/filter}
 * counterpart of {@code NodePredicateBuilder}.
 *
 * <p>The criteria a subscription and a node share are asked in exactly the same way here as there:
 * ids by {@code IN}, external ids split into indexed hashes plus {@code ILIKE} patterns, names as
 * patterns, timestamps as inclusive windows. The split itself is not re-derived — the filter hands
 * over hashes and patterns through {@code FilterPatterns}, so the two cannot drift into matching
 * different rows for the same body.
 *
 * <p><b>Nothing here multiplies rows, so callers must not set {@code distinct(true)}.</b> The
 * timeseries criterion is the only multi-valued one, and it asks its question with a correlated
 * {@code EXISTS} rather than joining the {@code @ManyToMany} — a join would return a subscription
 * once per matching timeseries, which is exactly the duplication the old
 * {@code SELECT DISTINCT s FROM SubscriptionEntity s JOIN s.timeseries t} query needed the DISTINCT
 * for, and DISTINCT is what makes a keyset {@code ORDER BY} awkward.
 */
public final class SubscriptionPredicateBuilder {

    /** The PL/pgSQL wrapper around ILIKE; Hibernate has no ILIKE operator of its own. */
    private static final String ILIKE_FN = "ILIKE_FN";

    private static final String TIMESERIES_REF = "timeseries";

    private SubscriptionPredicateBuilder() {
    }

    /** The predicates for one {@link SubscriptionFilter}. Null filter means no restriction at all. */
    public static List<Predicate> build(
            CriteriaBuilder cb,
            CriteriaQuery<?> query,
            Root<SubscriptionEntity> root,
            SubscriptionFilter filter
    ) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter == null) {
            return predicates;
        }

        // Identity selectors. An empty list places no restriction rather than matching nothing: an
        // empty IN is not valid SQL, and a caller who built a list and found nothing to put in it
        // means the former far more often than the latter.
        if (isNotEmpty(filter.getId())) {
            predicates.add(root.get("id").in(filter.getId()));
        }

        if (isNotEmpty(filter.getExternalId())) {
            // One list, two query strategies — the indexed hash column for literals, an ILIKE scan
            // only for the entries that actually carry a wildcard.
            List<Predicate> matches = new ArrayList<>();
            List<Long> exactHashes = filter.getExternalIdHashes();
            if (isNotEmpty(exactHashes)) {
                matches.add(root.get("externalIdHash").in(exactHashes));
            }
            for (String pattern : filter.getExternalIdPatterns()) {
                matches.add(ilike(cb, root, "externalId", pattern));
            }
            // All-blank entries leave nothing to match on; an empty list places no restriction.
            if (!matches.isEmpty()) {
                predicates.add(cb.or(matches.toArray(new Predicate[0])));
            }
        }

        // Names have no hashed column to fall back on, so every entry is a pattern — a literal one
        // still matches exactly, since WildcardPatterns escapes what SQL would read as a wildcard.
        List<String> namePatterns = filter.getNamePatterns();
        if (!namePatterns.isEmpty()) {
            List<Predicate> matches = namePatterns.stream()
                    .map(pattern -> ilike(cb, root, "name", pattern))
                    .toList();
            predicates.add(cb.or(matches.toArray(new Predicate[0])));
        }

        Predicate boundTo = boundToAnyOf(cb, query, root, filter.getTimeseries());
        if (boundTo != null) {
            predicates.add(boundTo);
        }

        addWindow(cb, predicates, root.get("dateCreated"), filter.getCreatedTime());
        addWindow(cb, predicates, root.get("lastUpdated"), filter.getLastUpdatedTime());

        return predicates;
    }

    /**
     * Subscriptions bound to at least one of the referenced timeseries, or null when nothing was
     * referenced. Each {@link IdCollection} may name a timeseries by id, by external id, or both,
     * and the two sets OR together — matching what {@code create} and {@code delete} accept, so the
     * same reference resolves the same way whichever endpoint it is handed to.
     */
    private static Predicate boundToAnyOf(
            CriteriaBuilder cb,
            CriteriaQuery<?> query,
            Root<SubscriptionEntity> root,
            Collection<IdCollection> references
    ) {
        if (references == null || references.isEmpty()) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();
        Set<Long> externalIdHashes = new LinkedHashSet<>();
        for (IdCollection reference : references) {
            if (reference == null) {
                continue;
            }
            if (reference.getId() != null) {
                ids.add(reference.getId());
            }
            if (reference.getExternalId() != null && !reference.getExternalId().isBlank()) {
                externalIdHashes.add(ExternalIds.hash(reference.getExternalId()));
            }
        }
        if (ids.isEmpty() && externalIdHashes.isEmpty()) {
            // Entries that named nothing. Restricting to them would be an empty IN; the rest of the
            // filter treats "supplied but empty" as no restriction, so this does too.
            return null;
        }

        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<SubscriptionEntity> subRoot = sub.from(SubscriptionEntity.class);
        Join<SubscriptionEntity, TimeseriesEntity> ts = subRoot.join(TIMESERIES_REF, JoinType.INNER);

        List<Predicate> anyReference = new ArrayList<>();
        if (!ids.isEmpty()) {
            anyReference.add(ts.get("id").in(ids));
        }
        if (!externalIdHashes.isEmpty()) {
            anyReference.add(ts.get("externalIdHash").in(externalIdHashes));
        }

        sub.select(cb.literal(1)).where(
                cb.equal(subRoot.get("id"), root.get("id")),
                cb.or(anyReference.toArray(new Predicate[0])));
        return cb.exists(sub);
    }

    private static void addWindow(CriteriaBuilder cb, List<Predicate> predicates, Path<OffsetDateTime> column,
                                  TimeFilter window) {
        if (window == null) {
            return;
        }
        // The wire type is ZonedDateTime, the column an OffsetDateTime. Converting here rather than
        // leaving the comparison to Hibernate's coercion keeps the instant the caller meant.
        if (window.getMin() != null) {
            predicates.add(cb.greaterThanOrEqualTo(column, window.getMin().toOffsetDateTime()));
        }
        if (window.getMax() != null) {
            predicates.add(cb.lessThanOrEqualTo(column, window.getMax().toOffsetDateTime()));
        }
    }

    /**
     * The {@code ORDER BY}: the sort column, then {@code id}. No null placement, because every
     * sortable subscription column is {@code NOT NULL} — see {@link SubscriptionSort}.
     */
    public static List<Order> orderBy(CriteriaBuilder cb, Root<SubscriptionEntity> root, SubscriptionSort sort) {
        Path<?> column = root.get(sort.attribute());
        Path<?> id = root.get("id");
        return sort.descending()
                ? List.of(cb.desc(column), cb.desc(id))
                : List.of(cb.asc(column), cb.asc(id));
    }

    /**
     * "Everything strictly after this cursor, in this order" — the keyset predicate that replaces an
     * {@code OFFSET}. A single two-part comparison, with none of the null-block handling the node
     * version needs, because no sortable column here can be null.
     */
    @SuppressWarnings("unchecked")
    public static Predicate keyset(CriteriaBuilder cb, Root<SubscriptionEntity> root,
                                   SubscriptionSort sort, PageCursor cursor) {
        Path<Comparable<Object>> column = (Path<Comparable<Object>>) (Path<?>) root.get(sort.attribute());
        Path<Comparable<Object>> id = (Path<Comparable<Object>>) (Path<?>) root.get("id");
        Comparable<Object> lastId = (Comparable<Object>) (Comparable<?>) Long.valueOf(cursor.id());
        Comparable<Object> boundary = (Comparable<Object>) (Comparable<?>) sortValue(sort, cursor.value());

        Predicate beyond = sort.descending() ? cb.lessThan(column, boundary) : cb.greaterThan(column, boundary);
        Predicate afterById = sort.descending() ? cb.lessThan(id, lastId) : cb.greaterThan(id, lastId);
        return cb.or(beyond, cb.and(cb.equal(column, boundary), afterById));
    }

    /** The last row's value for the sorted property, in the form {@link PageCursor} carries. */
    public static String cursorValue(SubscriptionEntity subscription, SubscriptionSort sort) {
        Object value = switch (sort.attribute()) {
            case "id" -> subscription.getId();
            case "externalId" -> subscription.getExternalId();
            case "name" -> subscription.getName();
            // Epoch millis, so the boundary survives a round trip through any client without a
            // timezone or precision question attached to it.
            case "dateCreated" -> subscription.getDateCreated() == null
                    ? null : subscription.getDateCreated().toInstant().toEpochMilli();
            case "lastUpdated" -> subscription.getLastUpdated() == null
                    ? null : subscription.getLastUpdated().toInstant().toEpochMilli();
            default -> null;
        };
        return value == null ? null : String.valueOf(value);
    }

    /** A cursor's boundary value, converted from its wire form to what the column compares as. */
    private static Comparable<?> sortValue(SubscriptionSort sort, String value) {
        return switch (sort.attribute()) {
            case "id" -> Long.valueOf(value);
            case "dateCreated", "lastUpdated" ->
                    Instant.ofEpochMilli(Long.parseLong(value)).atOffset(ZoneOffset.UTC);
            default -> value;
        };
    }

    private static Predicate ilike(CriteriaBuilder cb, Root<SubscriptionEntity> root, String attribute, String pattern) {
        return cb.isTrue(cb.function(ILIKE_FN, Boolean.class, root.get(attribute), cb.literal(pattern)));
    }

    private static boolean isNotEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }
}
