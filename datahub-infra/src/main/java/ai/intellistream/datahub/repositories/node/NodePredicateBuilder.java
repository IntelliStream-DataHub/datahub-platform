// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.datafilters.NodeFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.MapJoin;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Turns the shared {@link NodeFilter} criteria into Criteria predicates — the one implementation
 * behind {@code /datasets/filter}, {@code /resources/filter} and {@code /timeseries/filter}.
 *
 * <p>Every node type is a row in the same {@code node} table under single-table inheritance, so the
 * columns these predicates match are identical; only the discriminator differs. Three hand-written
 * copies of that logic is how the family drifted — one uppercased its ILIKE patterns and the others
 * did not, one forgot the discriminator entirely, and each learned about {@code name} and
 * {@code externalId} at a different time.
 *
 * <p><b>Nothing here multiplies rows, so callers must not set {@code distinct(true)}.</b> Every
 * multi-valued criterion asks its question with a correlated subquery: labels through a counting
 * one, metadata through an {@code EXISTS} per entry. Metadata used to use an inner join per entry,
 * which returned a node once per matching metadata row and forced every caller into
 * {@code SELECT DISTINCT} — and that DISTINCT is what made relevance ordering impossible, since
 * Postgres requires an {@code ORDER BY} expression to appear in the select list and a computed
 * {@code ts_rank} never can.
 *
 * <p>Data set scoping is <em>not</em> here. {@code dataSetId} needs the {@code BELONGS_TO} closure
 * and the caller's grants before it becomes a set of ids, both of which live above this layer; the
 * services expand it and hand the result to {@link #dataSetScope}.
 */
public final class NodePredicateBuilder {

    private static final String METADATA_REF = "metadata";
    private static final String LABELS_REF = "labelEntities";

    /** The PL/pgSQL wrapper around ILIKE; Hibernate has no ILIKE operator of its own. */
    private static final String ILIKE_FN = "ILIKE_FN";

    private NodePredicateBuilder() {
    }

    /**
     * The predicates for one {@link NodeFilter}, including the node-type discriminator.
     *
     * <p>The discriminator has to be passed explicitly, because the Criteria API does <em>not</em>
     * add it for these entities the way derived and JPQL queries do. A typed endpoint that omits it
     * returns rows of every node type and the transformer downstream presents them as whatever it
     * was asked for — which is what {@code /resources/filter} did by accident before it did so on
     * purpose. Passing no type at all is therefore a decision the caller states, not a default.
     *
     * @param nodeType the {@code NodeType} constant this query is restricted to
     */
    public static List<Predicate> build(
            CriteriaBuilder cb,
            CriteriaQuery<?> query,
            Root<? extends NodeEntity> root,
            NodeFilter filter,
            long nodeType
    ) {
        return build(cb, query, root, filter, List.of(nodeType));
    }

    /**
     * The same, restricted to a set of node types — or to none of them, which is how the generic
     * node query asks for every type at once.
     *
     * @param nodeTypes the {@code NodeType} constants this query is restricted to; empty or null
     *                  places no restriction, so rows of every type match
     */
    public static List<Predicate> build(
            CriteriaBuilder cb,
            CriteriaQuery<?> query,
            Root<? extends NodeEntity> root,
            NodeFilter filter,
            Collection<Long> nodeTypes
    ) {
        List<Predicate> predicates = new ArrayList<>();
        if (isNotEmpty(nodeTypes)) {
            Path<Object> discriminator = root.get("nodeType").get("id");
            predicates.add(nodeTypes.size() == 1
                    ? cb.equal(discriminator, nodeTypes.iterator().next())
                    : discriminator.in(nodeTypes));
        }

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
            // One list, two query strategies. Literal entries go through the indexed hash column,
            // derived from the lowercased id, which is how every other external-id lookup resolves;
            // only the wildcard entries need an ILIKE scan of the text column. Mixing the two in one
            // list therefore costs nothing when no wildcard was used, which is the common case.
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
        // Names and sources have no hashed column to fall back on, so every entry is a pattern —
        // a literal one still matches exactly, since WildcardPatterns escapes what SQL would
        // otherwise read as a wildcard.
        addAnyOf(cb, predicates, root, "name", filter.getNamePatterns());
        // The pattern goes in as the caller wrote it. ResourceService used to upper-case source
        // first, which is invisible for ASCII under a case-insensitive match but diverged from the
        // other two for no stated reason — and upper-casing a caller's pattern is not this layer's call.
        addAnyOf(cb, predicates, root, "source", filter.getSourcePatterns());

        if (isNotEmpty(filter.getLabels())) {
            predicates.add(hasAllLabels(cb, query, root, filter.getLabelHashes()));
        }

        if (filter.getMetadata() != null && !filter.getMetadata().isEmpty()) {
            // One EXISTS per entry, so several entries AND together ("has both of these") instead of
            // collapsing into one ambiguous join.
            //
            // These were inner joins on the map, one per entry, which matched the same rows but
            // returned each of them once per joined metadata row. That duplication was the sole
            // reason every query built here was SELECT DISTINCT — and DISTINCT is what made
            // relevance ordering impossible, because Postgres requires an ORDER BY expression to
            // appear in the select list and a computed ts_rank never can. Asking the question the
            // way hasAllLabels already asks its own removes the duplication at the source: no
            // multiplied rows, so no DISTINCT, so the search can order by rank.
            for (Map.Entry<String, String> entry : filter.getMetadata().entrySet()) {
                if (entry.getKey() == null && entry.getValue() == null) {
                    continue; // nothing to match on
                }
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<NodeEntity> subRoot = sub.from(NodeEntity.class);
                MapJoin<NodeEntity, String, String> mdJoin = subRoot.joinMap(METADATA_REF, JoinType.INNER);
                List<Predicate> conditions = new ArrayList<>();
                conditions.add(cb.equal(subRoot.get("id"), root.get("id")));
                if (entry.getKey() != null) {
                    conditions.add(cb.equal(mdJoin.key(), entry.getKey()));
                }
                // A null value means "this key, whatever it carries" — the meaning EventFilter has
                // always given it. Comparing against it instead produced `value = null`, which is
                // never true in SQL, so the entry silently matched nothing rather than doing what
                // the caller plainly asked for. The join alone is the "key exists" test.
                if (entry.getValue() != null) {
                    conditions.add(cb.equal(mdJoin.value(), entry.getValue()));
                }
                sub.select(cb.literal(1)).where(conditions.toArray(new Predicate[0]));
                predicates.add(cb.exists(sub));
            }
        }

        TimeFilter created = filter.getCreatedTime();
        if (created != null) {
            if (created.getMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dateCreated"), created.getMin()));
            }
            if (created.getMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dateCreated"), created.getMax()));
            }
        }

        TimeFilter updated = filter.getLastUpdatedTime();
        if (updated != null) {
            if (updated.getMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastUpdated"), updated.getMin()));
            }
            if (updated.getMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastUpdated"), updated.getMax()));
            }
        }

        return predicates;
    }

    /**
     * The full-text phrase predicate: nodes whose {@code name}, {@code externalId} or
     * {@code description} match, fuzzily and word-aware.
     *
     * <p>This is what makes {@code POST /x/search} exactly {@code POST /x/filter} plus a phrase.
     * It is one predicate among the rest, ANDed like any other, so the planner sees the whole
     * conjunction and {@code LIMIT} stops as soon as it has enough rows. See
     * {@link FtsMatchFunctionContributor} for why it had to become a registered function, and what
     * the two-query version got wrong.
     *
     * <p>Deliberately no ranking term: nothing in this schema sorts by {@code ts_rank}, and adding
     * one here would order the result without the caller asking. A search's order is its query's
     * business, not this predicate's.
     */
    public static Predicate fullTextMatch(CriteriaBuilder cb, Root<? extends NodeEntity> root, String phrase) {
        return cb.isTrue(cb.function(
                FtsMatchFunctionContributor.FUNCTION_NAME, Boolean.class,
                root.get("name"), root.get("externalId"), root.get("description"),
                cb.literal(phrase)));
    }

    /**
     * The {@code ORDER BY} for a full-text search: relevance first, then {@code id}.
     *
     * <p>The {@code id} term is not decoration. {@code ts_rank} ties constantly — every row matching
     * one common term scores the same — and without a tie-break the database is free to return a
     * different slice of an equally-ranked block on each identical request, which is the
     * nondeterminism ranking was added to remove.
     *
     * <p>Ordering by rank costs the early exit: the database has to score every matching row and
     * sort them before it can know which {@code limit} to return, where an unordered search could
     * stop at the first {@code limit} the index handed it. That is inherent to ranking, not to how
     * this is written.
     */
    public static List<Order> searchOrderBy(CriteriaBuilder cb, Root<? extends NodeEntity> root, String phrase) {
        return List.of(
                cb.desc(searchRank(cb, root, phrase)),
                cb.asc(root.get("id")));
    }

    /** The cursor property name a relevance-ordered search pages by. */
    public static final String RELEVANCE = "relevance";

    /**
     * "Everything strictly after this cursor, in relevance order" — the keyset predicate that lets
     * a search be paged instead of truncated at {@code limit}.
     *
     * <p>Search orders by {@code (rank desc, id asc)}, which is already a total order, so a
     * position in it is fully described by the last row's rank and id. The rank is not a column:
     * it is recomputed per row for this phrase, which is why the query has to select it in order
     * to hand one back.
     */
    public static Predicate searchKeyset(CriteriaBuilder cb, Root<? extends NodeEntity> root,
                                         String phrase, PageCursor cursor) {
        Expression<Float> rank = searchRank(cb, root, phrase);
        float lastRank = Float.parseFloat(cursor.value());
        Path<Long> id = root.get("id");
        long lastId = Long.parseLong(cursor.id());
        return cb.or(
                cb.lessThan(rank, lastRank),
                cb.and(cb.equal(rank, lastRank), cb.greaterThan(id, lastId)));
    }

    /** The relevance score of {@code phrase} against this node, for {@link #searchOrderBy}. */
    public static Expression<Float> searchRank(CriteriaBuilder cb, Root<? extends NodeEntity> root, String phrase) {
        return cb.function(
                FtsMatchFunctionContributor.RANK_FUNCTION_NAME, Float.class,
                root.get("name"), root.get("externalId"), root.get("description"),
                cb.literal(phrase));
    }

    /**
     * Restrict to nodes in these data sets. The ids must already be the expanded
     * {@code BELONGS_TO} closure, intersected with what the caller may read.
     *
     * <p>Never call this with an empty collection — that is "narrow to no data sets", and an empty
     * {@code IN} is not valid SQL. A caller reaching that state should return an empty result
     * without running the query at all; dropping the predicate instead would widen it to
     * everything.
     */
    public static Predicate dataSetScope(Root<? extends NodeEntity> root, Collection<Long> expandedDataSetIds) {
        if (expandedDataSetIds == null || expandedDataSetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "dataSetScope needs at least one data set id; an empty scope means the caller should return no rows");
        }
        // The implicit inner join on dataSet also drops orphan nodes (those with no data set),
        // which a caller without all-datasets access cannot see anyway.
        return root.get("dataSet").get("id").in(expandedDataSetIds);
    }

    /**
     * A node carries every one of these labels. Counts the node's matching labels in a correlated
     * subquery and requires the count to equal how many distinct labels were asked for, rather than
     * adding a join per label — so the cost does not grow with the size of the filter, and the
     * outer query keeps one row per node.
     *
     * <p>Matched on {@code label.hash}, which carries the unique index, not the name column.
     */
    private static Predicate hasAllLabels(
            CriteriaBuilder cb,
            CriteriaQuery<?> query,
            Root<? extends NodeEntity> root,
            List<Long> labelHashes
    ) {
        List<Long> wanted = labelHashes.stream().distinct().toList();
        if (wanted.isEmpty()) {
            // Every supplied name was blank. Asking for no labels restricts nothing.
            return cb.conjunction();
        }

        Subquery<Long> sub = query.subquery(Long.class);
        Root<NodeEntity> subRoot = sub.from(NodeEntity.class);
        Join<NodeEntity, Label> labelJoin = subRoot.join(LABELS_REF, JoinType.INNER);
        sub.select(cb.countDistinct(labelJoin.get("hash")))
                .where(
                        cb.equal(subRoot.get("id"), root.get("id")),
                        labelJoin.get("hash").in(wanted)
                );
        return cb.equal(sub, (long) wanted.size());
    }


    /**
     * The {@code ORDER BY} for a sorted node query: the sort column, then {@code id}.
     *
     * <p>When the column is nullable the null block is placed explicitly — last ascending, first
     * descending — with a {@code CASE} rather than a {@code NULLS} clause, which JPA Criteria has
     * no portable spelling for. That {@code CASE} does cost the index, so it is emitted only for
     * columns that can actually be null; the not-null ones sort plainly.
     */
    public static List<Order> orderBy(CriteriaBuilder cb, Root<? extends NodeEntity> root, NodeSort sort) {
        Path<?> column = sortPath(root, sort);
        return List.of(
                order(cb, column, sort.descending(), !sort.nullsLast()),
                order(cb, root.get("id"), sort.descending(), false));
    }

    /**
     * One {@code ORDER BY} term, with explicit null placement where the dialect can express it.
     *
     * <p>Rendered as a real {@code NULLS FIRST}/{@code NULLS LAST} rather than a
     * {@code CASE WHEN col IS NULL} sort key, which is the portable-looking trick that does not
     * work here: these queries are {@code SELECT DISTINCT}, and Postgres requires every
     * {@code ORDER BY} expression to appear in the select list, which a computed case never does.
     *
     * <p>The fallback covers a plain JPA {@link CriteriaBuilder}, which has no null-precedence
     * spelling at all. Hibernate always supplies its own at runtime, so in practice that branch is
     * only reached by a mocked builder in a unit test; it accepts the database's default placement
     * rather than pretending to control it.
     */
    private static Order order(CriteriaBuilder cb, Path<?> path, boolean descending, boolean nullsFirst) {
        if (cb instanceof HibernateCriteriaBuilder hcb) {
            return descending ? hcb.desc(path, nullsFirst) : hcb.asc(path, nullsFirst);
        }
        return descending ? cb.desc(path) : cb.asc(path);
    }

    /**
     * "Everything strictly after this cursor, in this order" — the keyset predicate that replaces
     * an {@code OFFSET}.
     *
     * <p>{@code OFFSET n} makes the database produce and discard n rows on every page, so the cost
     * grows with depth; worse, a row written before the current position shifts every later one, so
     * the next page repeats or skips one. A keyset names a position in the order instead, which is
     * a range the index can seek to and which nothing written elsewhere can shift.
     *
     * <p>The null block is why this is not a single comparison. A nullable column sorts in two
     * parts, and a cursor sits in one of them: from inside the non-null block the remainder is
     * "later in that block, plus the whole null block"; from inside the null block it is only
     * "later in the null block". Comparing against SQL NULL instead would match nothing and quietly
     * end the walk early — a short page, not an error.
     */
    @SuppressWarnings("unchecked")
    public static Predicate keyset(CriteriaBuilder cb, Root<? extends NodeEntity> root,
                                   NodeSort sort, PageCursor cursor) {
        Path<Comparable<Object>> column = (Path<Comparable<Object>>) sortPath(root, sort);
        Path<Comparable<Object>> id = (Path<Comparable<Object>>) (Path<?>) root.get("id");
        Comparable<Object> lastId = (Comparable<Object>) (Comparable<?>) Long.valueOf(cursor.id());

        Predicate afterById = sort.descending() ? cb.lessThan(id, lastId) : cb.greaterThan(id, lastId);

        if (cursor.value() == null) {
            // The previous page ended inside the null block; only the rest of that block remains,
            // plus the not-null block when nulls come first (descending).
            Predicate withinNulls = cb.and(cb.isNull(column), afterById);
            return sort.nullsLast() ? withinNulls : cb.or(withinNulls, cb.isNotNull(column));
        }

        Comparable<Object> boundary = (Comparable<Object>) (Comparable<?>) sortValue(sort, cursor.value());
        Predicate beyond = sort.descending() ? cb.lessThan(column, boundary) : cb.greaterThan(column, boundary);
        Predicate sameValue = cb.and(cb.equal(column, boundary), afterById);
        Predicate afterInBlock = cb.or(beyond, sameValue);

        if (!sort.nullable()) {
            return afterInBlock;
        }
        // Nulls last: the null block still lies ahead. Nulls first: it is already behind us, and
        // the not-null guard keeps it there — without it, NULL rows would slip back in.
        return sort.nullsLast()
                ? cb.or(cb.and(cb.isNotNull(column), afterInBlock), cb.isNull(column))
                : cb.and(cb.isNotNull(column), afterInBlock);
    }

    /** The last row's value for the sorted property, in the form {@link PageCursor} carries. */
    public static String cursorValue(NodeEntity node, NodeSort sort) {
        Object value = switch (sort.attribute()) {
            case "id" -> node.getId();
            case "externalId" -> node.getExternalId();
            case "name" -> node.getName();
            case "source" -> node.getSource();
            case "description" -> node.getDescription();
            // Epoch millis, so the boundary survives a round trip through any client without a
            // timezone or precision question attached to it.
            case "dateCreated" -> node.getDateCreated() == null ? null : node.getDateCreated().toInstant().toEpochMilli();
            case "lastUpdated" -> node.getLastUpdated() == null ? null : node.getLastUpdated().toInstant().toEpochMilli();
            case "dataSet" -> node.getDataSet() == null ? null : node.getDataSet().getId();
            default -> null;
        };
        return value == null ? null : String.valueOf(value);
    }

    private static Path<?> sortPath(Root<? extends NodeEntity> root, NodeSort sort) {
        // dataSetId is a relation, so the ordering has to reach through it to the id rather than
        // sorting on the association itself.
        return sort.isRelation() ? root.get("dataSet").get("id") : root.get(sort.attribute());
    }

    /** A cursor's boundary value, converted from its wire form to what the column compares as. */
    private static Comparable<?> sortValue(NodeSort sort, String value) {
        return switch (sort.attribute()) {
            case "id", "dataSet" -> Long.valueOf(value);
            case "dateCreated", "lastUpdated" ->
                    Instant.ofEpochMilli(Long.parseLong(value)).atZone(ZoneOffset.UTC);
            default -> value;
        };
    }

    /** OR the patterns together against one column, if there are any. */
    private static void addAnyOf(CriteriaBuilder cb, List<Predicate> predicates,
                                 Root<? extends NodeEntity> root, String attribute, List<String> patterns) {
        if (patterns.isEmpty()) {
            return;
        }
        List<Predicate> matches = patterns.stream()
                .map(pattern -> ilike(cb, root, attribute, pattern))
                .toList();
        predicates.add(cb.or(matches.toArray(new Predicate[0])));
    }

    private static Predicate ilike(CriteriaBuilder cb, Root<? extends NodeEntity> root, String attribute, String pattern) {
        return cb.isTrue(cb.function(ILIKE_FN, Boolean.class, root.get(attribute), cb.literal(pattern)));
    }

    private static boolean isNotEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
