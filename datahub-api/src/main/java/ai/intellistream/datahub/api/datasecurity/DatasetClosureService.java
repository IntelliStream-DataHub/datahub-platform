// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The single authority for dataset membership: given a dataset, which datasets are beneath it.
 *
 * <p>Two callers, one answer. {@link #closureOfExternalIds(Collection)} resolves an access grant — a dataset
 * named by {@code externalId} in a Keycloak organization group path such as
 * {@code /datasets/data_set_sap/read} — to every dataset it covers, which is what keeps the number
 * of groups an administrator maintains proportional to access domains rather than to dataset count.
 * {@link #closureOf(Collection)} answers the same question for a query filter, so
 * {@code dataSetId=X} means the same set of rows that a grant on X would let you read.
 *
 * <p>Both walk the {@code BELONGS_TO} hierarchy downward through the recursive closure in
 * {@code DataSetRepository.findDatasetClosure}. The filter path used to walk the Neo4j mirror
 * instead ({@code Neo4JService.findDataSetDescendantIds}, now removed): two implementations of one
 * concept, over two stores, one of which lags. A timeseries added to a freshly created child
 * dataset was authorized correctly but invisible to {@code dataSetId} filtering until the stateful
 * consumer caught up. There is now one query, one store, one cache.
 *
 * <h2>Postgres, not Neo4j</h2>
 * The graph exists in both, but the Neo4j mirror is written asynchronously by
 * {@code datahub-stateful-consumer} off the resources topic. Resolving an ACL through it would lag
 * behind writes: a freshly created child dataset would be invisible, and a freshly detached one
 * would stay readable. Authorization reads the synchronous source, which is the {@code edge} table.
 *
 * <h2>Caching and invalidation</h2>
 * The whole chain (external ids, node ids, closure) is memoised as one entry keyed on the grant
 * set, so callers holding identical grants — everyone on a team, typically — share it and the
 * recursive query runs once per distinct grant set rather than once per user.
 *
 * <p>Invalidation is a per-tenant <strong>generation counter</strong> in Valkey rather than key
 * deletion: bumping one integer invalidates every closure for the tenant at once, with no key
 * scanning, and superseded entries fall out on their own TTL. A cached entry records the generation
 * it was computed at, and is recomputed when that no longer matches. Both the counter and the entry
 * are read in a single {@code MGET}, so a cache hit costs one Valkey round trip.
 *
 * <p>Call {@link #invalidate()} whenever something changes what a grant covers: a dataset created
 * or deleted, an {@code externalId} renamed, or a {@code BELONGS_TO} edge added or removed.
 */
@Service
@Slf4j
public class DatasetClosureService {

    private static final String GENERATION_KEY_PREFIX = "acl:gen:";
    /** Closures keyed by grant set (the {@link #closureOfExternalIds(Collection)} path). */
    private static final String GRANT_KEY_PREFIX = "acl:closure:";
    /** Closures keyed by the set of data sets asked for (the {@link #closureOf(Collection)} path). */
    private static final String DATASET_KEY_PREFIX = "acl:dsclosure:";

    /** The relationship that forms the dataset hierarchy. Stored upper-cased. */
    public static final String BELONGS_TO = "BELONGS_TO";

    private final DataSetRepository dataSetRepository;
    private final ValkeyService valkeyService;
    private final JsonMapper jsonMapper;
    private final long cacheTtlSeconds;

    /** A closure plus the tenant generation it was computed at. */
    record CachedClosure(List<Long> datasetIds, long generation) {}

    public DatasetClosureService(
            DataSetRepository dataSetRepository,
            ValkeyService valkeyService,
            JsonMapper jsonMapper,
            @Value("${datahub.acl.closure.ttl:30m}") Duration cacheTtl) {
        this.dataSetRepository = dataSetRepository;
        this.valkeyService = valkeyService;
        this.jsonMapper = jsonMapper;
        this.cacheTtlSeconds = cacheTtl.toSeconds();
    }

    /**
     * Every dataset id covered by the given dataset external ids: the datasets themselves plus
     * everything beneath them in the {@code BELONGS_TO} hierarchy.
     *
     * <p>External ids that resolve to nothing are silently dropped. A group naming a dataset that
     * does not exist (a typo, or one deleted since) simply grants nothing, and the resulting
     * smaller set is cached like any other — a later dataset creation bumps the generation and
     * recomputes it.
     */
    @Transactional(readOnly = true)
    public Set<Long> closureOfExternalIds(Collection<String> datasetExternalIds) {
        if (datasetExternalIds == null || datasetExternalIds.isEmpty()) {
            return Set.of();
        }
        // Sorted and de-duplicated so callers holding the same grants in a different order share
        // one cache entry.
        TreeSet<String> normalised = new TreeSet<>(datasetExternalIds);
        return cached(GRANT_KEY_PREFIX, fingerprint(normalised), () -> computeClosure(normalised));
    }

    /**
     * The given data sets plus every data set beneath them in the {@code BELONGS_TO} hierarchy.
     *
     * <p>This is what {@code dataSetId}/{@code dataSetIds} means on a query filter: the named data
     * sets and everything under them, the same set a grant on them covers. Sharing the query with
     * {@link #closureOfExternalIds(Collection)} is the point — a filter that expanded differently from the ACL
     * would return rows the caller could not have reached by grant, or hide rows they could.
     *
     * <p>One recursive query for the whole set rather than one per root; the underlying closure
     * already takes a set. An empty input returns empty: a caller that asked to be narrowed to no
     * data sets gets no rows, never "no restriction".
     */
    @Transactional(readOnly = true)
    public Set<Long> closureOf(Collection<Long> dataSetIds) {
        if (dataSetIds == null || dataSetIds.isEmpty()) {
            return Set.of();
        }
        TreeSet<Long> roots = new TreeSet<>(dataSetIds);
        String discriminator = Long.toHexString(
                ExternalIds.hash(roots.stream().map(String::valueOf).collect(Collectors.joining(" "))));
        return cached(DATASET_KEY_PREFIX, discriminator,
                () -> new LinkedHashSet<>(dataSetRepository.findDatasetClosure(
                        roots, NodeType.DATASET, BELONGS_TO)));
    }

    /**
     * The closure of data sets named by reference — each entry carrying an id or an externalId, as
     * every {@code dataSetIds} filter field now does.
     *
     * <p>Resolving those references was written out three times, once per service, and drifted:
     * the resource filter accepted ids only, the event filter accepted both, and each expanded at a
     * different point in the request. One implementation, next to the closure it feeds.
     *
     * <p>External ids resolve in a single query. A reference naming no data set contributes
     * nothing — and since an empty input yields an empty closure, a filter that names only unknown
     * data sets correctly matches no rows rather than silently matching all of them.
     */
    @Transactional(readOnly = true)
    public Set<Long> closureOfReferences(Collection<IdCollection> references) {
        if (references == null || references.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = new ArrayList<>();
        Set<Long> externalIdHashes = new LinkedHashSet<>();
        for (IdCollection reference : references) {
            if (reference == null) {
                continue;
            }
            if (reference.getId() != null) {
                ids.add(reference.getId());
            } else if (reference.getExternalIdHash() != null) {
                externalIdHashes.add(reference.getExternalIdHash());
            }
        }
        if (!externalIdHashes.isEmpty()) {
            ids.addAll(dataSetRepository.findDatasetIdsByExternalIdHashIn(externalIdHashes, NodeType.DATASET));
        }
        return closureOf(ids);
    }

    /**
     * Read a closure through the cache, computing it on a miss.
     *
     * <p>Both key spaces share the tenant's generation counter, because the things that change what
     * a grant covers — a dataset created or deleted, an {@code externalId} renamed, a
     * {@code BELONGS_TO} edge added or removed — are exactly the things that change what is beneath
     * a dataset. One {@code INCR} invalidates both.
     */
    private Set<Long> cached(String keyPrefix, String discriminator, Supplier<Set<Long>> compute) {
        String tenantId = requireTenant();
        String cacheKey = keyPrefix + tenantId + ":" + discriminator;
        String generationKey = GENERATION_KEY_PREFIX + tenantId;

        long generation = 0L;
        try {
            // One round trip for both: the generation is needed to judge the entry, so it cannot
            // be folded into the cache key without a second read.
            Map<String, String> values = valkeyService.multiGet(List.of(generationKey, cacheKey));
            generation = parseGeneration(values.get(generationKey));
            String cachedEntry = values.get(cacheKey);
            if (cachedEntry != null) {
                CachedClosure entry = jsonMapper.readValue(cachedEntry, CachedClosure.class);
                if (entry.generation() == generation) {
                    return Set.copyOf(entry.datasetIds());
                }
            }
        } catch (Exception e) {
            // A cache failure must degrade to a live query, never to a denial.
            log.warn("Closure cache read failed for tenant {}: {}", tenantId, e.getMessage());
        }

        Set<Long> closure = compute.get();
        writeCache(cacheKey, new CachedClosure(List.copyOf(closure), generation));
        return closure;
    }

    /**
     * Bump the tenant's ACL generation, invalidating every cached closure for it.
     *
     * <p>A single {@code INCR}, so this is cheap enough to call from any write path that changes
     * what a grant covers, and safe under concurrent callers across api instances.
     */
    public void invalidate() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        try {
            long generation = valkeyService.increment(GENERATION_KEY_PREFIX + tenantId, 1);
            log.debug("Bumped dataset-ACL generation for tenant {} to {}", tenantId, generation);
        } catch (Exception e) {
            // Losing a bump means grants stay stale until the entry's TTL expires. Loud, because
            // it is a correctness problem rather than a performance one.
            log.error("Could not bump dataset-ACL generation for tenant {}; cached closures stay " +
                    "stale for up to {}s", tenantId, cacheTtlSeconds, e);
        }
    }

    private Set<Long> computeClosure(Collection<String> externalIds) {
        List<Long> hashes = new ArrayList<>(externalIds.size());
        for (String externalId : externalIds) {
            hashes.add(ExternalIds.hash(externalId));
        }
        List<Long> rootIds = dataSetRepository.findDatasetIdsByExternalIdHashIn(hashes, NodeType.DATASET);
        if (rootIds.isEmpty()) {
            log.debug("No dataset resolved from grant external ids {}", externalIds);
            return Set.of();
        }
        List<Long> closure = dataSetRepository.findDatasetClosure(rootIds, NodeType.DATASET, BELONGS_TO);
        return new LinkedHashSet<>(closure);
    }

    private void writeCache(String cacheKey, CachedClosure entry) {
        try {
            valkeyService.setString(cacheKey, jsonMapper.writeValueAsString(entry), cacheTtlSeconds);
        } catch (Exception e) {
            log.warn("Closure cache write failed for {}: {}", cacheKey, e.getMessage());
        }
    }

    private static long parseGeneration(String raw) {
        if (raw == null || raw.isBlank()) {
            // No counter yet: generation 0. The first invalidate() makes it 1, so entries written
            // before any invalidation are correctly superseded.
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Stable short key for a grant set. xx3 over the joined ids, matching the codebase's hashing.
     *
     * <p>Hashed through {@link ExternalIds#hash} so the key folds case, exactly as the closure it
     * keys does. Grants differing only in case resolve to the same datasets, so they must not
     * occupy two cache entries that could then diverge.
     */
    private static String fingerprint(Collection<String> sortedExternalIds) {
        return Long.toHexString(ExternalIds.hash(String.join(" ", sortedExternalIds)));
    }

    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant in TenantContext; cannot expand dataset grants");
        }
        return tenantId;
    }
}
