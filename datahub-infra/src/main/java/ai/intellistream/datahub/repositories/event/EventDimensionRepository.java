// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.event;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maintains and queries the small Postgres dimension tables holding the DISTINCT
 * (data_set_id, value) pairs for event type / sub_type / status / source (see migrations V29 and V30).
 *
 * <p>All SQL identifiers come from the fixed {@link Dimension} enum — never from caller input — so
 * the table/column interpolation carries no injection risk; values, ACL ids, and the search term are
 * always bound parameters.
 *
 * <p>A per-instance positive cache ({@link #recentCache}) records (dim, data_set_id, value) keys we
 * have recently inserted, so the steady state — events whose categories already exist — issues no
 * Postgres write at all. The cache is purely an optimization: it only ever skips an upsert that
 * {@code ON CONFLICT DO NOTHING} would have no-op'd anyway, so correctness never depends on it. A TTL
 * bounds how long a "known" belief survives, so a value retracted by the weekly reconcile is
 * re-inserted by the next event within the TTL rather than lingering missing until the next rebuild.
 */
@Slf4j
@Repository
public class EventDimensionRepository {

    /** Largest number of rows per INSERT, keeping the bound-parameter count well under Postgres' limit. */
    private static final int INSERT_CHUNK = 1000;

    @PersistenceContext
    private EntityManager entityManager;

    private final long cacheTtlMillis;
    /** Access-order LRU of recently-inserted keys -> expiry epoch millis. Guard every access with its monitor. */
    private final Map<String, Long> recentCache;

    public EventDimensionRepository(
            // Sized above "a few hundred" deliberately: the cache key includes data_set_id, so the live
            // working set is roughly (active datasets x active values), not just the ~500/1000/100 raw
            // cardinalities — too small a cache would thrash and defeat the point.
            @Value("${datahub.event-dimension.cache.max-size:10000}") int cacheMaxSize,
            @Value("${datahub.event-dimension.cache.ttl-seconds:3600}") long cacheTtlSeconds) {
        this.cacheTtlMillis = cacheTtlSeconds * 1000L;
        this.recentCache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > cacheMaxSize;
            }
        };
    }

    /** The event categorical dimensions, each mapping to its V29 table and value column. */
    public enum Dimension {
        TYPE("event_type_dim", "type"),
        SUB_TYPE("event_sub_type_dim", "sub_type"),
        STATUS("event_status_dim", "status"),
        SOURCE("event_source_dim", "source");

        private final String table;
        private final String column;

        Dimension(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }

    /**
     * Idempotently record the given (data_set_id, value) pairs, skipping any we have recently inserted
     * (see {@link #recentCache}); if all are cached this issues no SQL. Runs in its own transaction
     * ({@code REQUIRES_NEW}) so a failure here — or a rollback of the caller's transaction — never
     * affects (and is never affected by) the event create/update it rides along with. {@code ON
     * CONFLICT DO NOTHING} makes redeliveries and retries harmless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(Dimension dim, Collection<DatasetValue> pairs) {
        List<DatasetValue> toInsert = new ArrayList<>();
        for (DatasetValue dv : dedupe(pairs)) {
            if (!isCached(dim, dv)) {
                toInsert.add(dv);
            }
        }
        if (toInsert.isEmpty()) {
            return;
        }
        insertPairs(dim, toInsert, false);
        // Cache only after a successful insert, so a failed upsert is never remembered as done.
        for (DatasetValue dv : toInsert) {
            cache(dim, dv);
        }
    }

    /**
     * Replace the entire dimension table with {@code trueSet} (the authoritative DISTINCT set scanned
     * from ClickHouse). DELETE + INSERT inside one transaction; concurrent readers keep seeing the
     * previous contents until commit (MVCC), so there is no empty window. This is what retracts values
     * whose last event was deleted or whose category changed.
     *
     * <p>Bypasses the positive cache entirely. The rebuilding instance's own stale entries are dropped
     * here; other instances rely on the cache TTL to re-learn retracted values.
     */
    @Transactional
    public void rebuild(Dimension dim, Collection<DatasetValue> trueSet) {
        entityManager.createNativeQuery("DELETE FROM " + dim.table).executeUpdate();
        insertPairs(dim, dedupe(trueSet), true);
        clearCache(dim);
    }

    /**
     * Same as calling {@link #rebuild} once per {@link Dimension}, but issues a single SQL statement
     * that deletes from all four tables instead of four separate {@code DELETE}s. Postgres has no
     * UNION-of-DELETEs (UNION combines SELECT-shaped result sets; a bare DELETE has none), but a
     * {@code WITH} clause chaining {@code DELETE ... RETURNING} CTEs does the same job as one
     * statement: every data-modifying CTE runs exactly once, in full, regardless of whether the
     * trailing SELECT reads its output — so the trailing {@code SELECT 1} is just an inert
     * terminator. Each DELETE still only takes Postgres' ROW EXCLUSIVE lock, so — unlike TRUNCATE —
     * concurrent readers keep seeing the previous rows via MVCC until commit, same as {@link
     * #rebuild}.
     */
    @Transactional
    public void rebuildAll(Map<Dimension, List<DatasetValue>> trueSets) {
        String ctes = Arrays.stream(Dimension.values())
                .map(d -> "del_" + d.name().toLowerCase() + " AS (DELETE FROM " + d.table + " RETURNING 1)")
                .collect(Collectors.joining(", "));
        entityManager.createNativeQuery("WITH " + ctes + " SELECT 1").getSingleResult();
        for (Dimension dim : Dimension.values()) {
            insertPairs(dim, dedupe(trueSets.getOrDefault(dim, List.of())), true);
            clearCache(dim);
        }
    }

    /**
     * Distinct values for the dimension, dataset-ACL-aware and optionally substring-filtered.
     *
     * @param allowedDataSetIds {@code null} = read every dataset (no filter); empty = no readable
     *                          datasets, returns an empty list; otherwise restricts to those ids.
     * @param query             optional case-insensitive substring; {@code null}/blank lists everything.
     * @param limit             maximum number of values to return.
     */
    @Transactional(readOnly = true)
    public List<String> listDistinct(Dimension dim, Collection<Long> allowedDataSetIds, String query, int limit) {
        // Empty allow-set means "nothing is readable" — short-circuit rather than emit `IN ()`.
        if (allowedDataSetIds != null && allowedDataSetIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> conditions = new ArrayList<>();
        if (allowedDataSetIds != null) {
            conditions.add("data_set_id IN (:acl)");
        }
        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            conditions.add(dim.column + " ILIKE :q ESCAPE '\\'");
        }

        String sql = "SELECT DISTINCT " + dim.column + " FROM " + dim.table
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions))
                + " ORDER BY " + dim.column;

        Query q = entityManager.createNativeQuery(sql);
        if (allowedDataSetIds != null) {
            q.setParameter("acl", allowedDataSetIds);
        }
        if (hasQuery) {
            q.setParameter("q", "%" + escapeLike(query.trim()) + "%");
        }
        q.setMaxResults(Math.max(1, limit));

        @SuppressWarnings("unchecked")
        List<String> results = q.getResultList();
        return results;
    }

    private void insertPairs(Dimension dim, List<DatasetValue> pairs, boolean afterDelete) {
        if (pairs.isEmpty()) {
            return;
        }
        // `afterDelete` rows go into a freshly emptied table, but keep ON CONFLICT DO NOTHING anyway so a
        // concurrent write-path upsert racing the rebuild can't break it.
        for (int from = 0; from < pairs.size(); from += INSERT_CHUNK) {
            List<DatasetValue> chunk = pairs.subList(from, Math.min(from + INSERT_CHUNK, pairs.size()));
            StringBuilder sql = new StringBuilder("INSERT INTO ")
                    .append(dim.table).append(" (data_set_id, ").append(dim.column).append(") VALUES ");
            int p = 1;
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append("(?").append(p++).append(",?").append(p++).append(')');
            }
            sql.append(" ON CONFLICT DO NOTHING");

            Query query = entityManager.createNativeQuery(sql.toString());
            int idx = 1;
            for (DatasetValue dv : chunk) {
                query.setParameter(idx++, dv.dataSetId());
                query.setParameter(idx++, dv.value());
            }
            query.executeUpdate();
        }
    }

    // --- positive cache ------------------------------------------------------------------------

    private boolean isCached(Dimension dim, DatasetValue dv) {
        String key = cacheKey(dim, dv);
        synchronized (recentCache) {
            Long expiry = recentCache.get(key);
            if (expiry == null) {
                return false;
            }
            if (expiry < System.currentTimeMillis()) {
                recentCache.remove(key);
                return false;
            }
            return true;
        }
    }

    private void cache(Dimension dim, DatasetValue dv) {
        String key = cacheKey(dim, dv);
        synchronized (recentCache) {
            recentCache.put(key, System.currentTimeMillis() + cacheTtlMillis);
        }
    }

    private void clearCache(Dimension dim) {
        String prefix = dim.name() + ' ';
        synchronized (recentCache) {
            recentCache.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    private static String cacheKey(Dimension dim, DatasetValue dv) {
        return dim.name() + ' ' + dv.dataSetId() + ' ' + dv.value();
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Drop nulls/blanks and collapse duplicate pairs before issuing the insert. */
    private static List<DatasetValue> dedupe(Collection<DatasetValue> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return new ArrayList<>();
        }
        Set<DatasetValue> seen = new LinkedHashSet<>();
        for (DatasetValue dv : pairs) {
            if (dv != null && dv.value() != null && !dv.value().isBlank()) {
                seen.add(dv);
            }
        }
        return new ArrayList<>(seen);
    }

    /** Escape LIKE/ILIKE wildcards so a user-supplied term is matched literally (ESCAPE '\'). */
    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
