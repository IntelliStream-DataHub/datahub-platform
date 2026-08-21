// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.models.policy.NamingPolicy;
import ai.intellistream.datahub.repositories.policy.NamingPolicyRepository;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which naming policy governs a given data set, and caches the answer.
 *
 * <h2>Resolution</h2>
 * The data set's own naming policy if it has one, else the tenant policy, else the shipped default.
 * <strong>Most specific wins, with no merging.</strong> A data-set policy <em>replaces</em> the
 * tenant policy rather than adding to it, because a partially-overridden naming rule — this data
 * set's preset but the tenant's near-duplicate mode — is not something anyone can reason about, and
 * the only way to find out what is in force would be to read both and guess at the precedence of
 * each field.
 *
 * <h2>Caching</h2>
 * Resolved once per request, never per item. Uses the same per-tenant generation counter as
 * {@code DatasetClosureService}: one integer bumped on any policy write invalidates the whole
 * tenant's cached policy set at once, with no key scanning, and superseded entries expire on their
 * own TTL.
 *
 * <p>A cache failure degrades to a live query, and a <em>query</em> failure degrades to the shipped
 * default rather than to an exception. That second choice is worth stating: if the policy store is
 * unreachable, the alternative is failing every write in the tenant, and refusing all ingest
 * because a governance rule could not be read is a worse outcome than applying the default rule.
 * The default is not permissive — it still rejects near-duplicates — so this fails closed on the
 * protection that matters and open on the configurability.
 */
@Slf4j
@Service
public class NamingPolicyResolver {

    private static final String GENERATION_KEY_PREFIX = "policy:gen:";
    private static final String POLICY_KEY_PREFIX = "policy:naming:";

    private final NamingPolicyRepository namingPolicyRepository;
    private final ValkeyService valkeyService;
    private final JsonMapper jsonMapper;
    private final long cacheTtlSeconds;

    public NamingPolicyResolver(NamingPolicyRepository namingPolicyRepository,
                                ValkeyService valkeyService,
                                JsonMapper jsonMapper,
                                @Value("${datahub.policy.naming.cache.ttl:30m}") Duration cacheTtl) {
        this.namingPolicyRepository = namingPolicyRepository;
        this.valkeyService = valkeyService;
        this.jsonMapper = jsonMapper;
        this.cacheTtlSeconds = cacheTtl.toSeconds();
    }

    /**
     * The policy set for the current tenant: the tenant default plus any per-data-set overrides.
     *
     * <p>Returned as a snapshot so a batch resolves against one consistent view even if a policy is
     * edited mid-request.
     */
    @Transactional(readOnly = true)
    public ResolvedPolicies resolveForTenant() {
        String tenantId = TenantContext.getTenantId();
        String generationKey = GENERATION_KEY_PREFIX + tenantId;
        String cacheKey = POLICY_KEY_PREFIX + tenantId;

        long generation = 0L;
        try {
            // One round trip for both: the generation is needed to judge the entry, so folding it
            // into the key instead would still cost a second read to learn the current value.
            Map<String, String> values = valkeyService.multiGet(List.of(generationKey, cacheKey));
            generation = parseGeneration(values.get(generationKey));
            String cached = values.get(cacheKey);
            if (cached != null) {
                CachedPolicies entry = jsonMapper.readValue(cached, CachedPolicies.class);
                if (entry.generation() == generation) {
                    return assemble(entry.rows());
                }
            }
        } catch (Exception e) {
            // A cache failure degrades to a live query, never to a write failure.
            log.warn("Naming-policy cache read failed for tenant {}: {}", tenantId, e.getMessage());
        }

        List<NamingPolicyRepository.NamingPolicyRow> rows;
        try {
            rows = namingPolicyRepository.findAll();
        } catch (Exception e) {
            log.error("Could not read naming policies for tenant {}; applying the shipped default "
                    + "(qualified_tag and the near-duplicate guard, both warning) so writes are not blocked", tenantId, e);
            return new ResolvedPolicies(NamingPolicy.shippedDefault(), Map.of());
        }

        writeCache(cacheKey, new CachedPolicies(List.copyOf(rows), generation));
        return assemble(rows);
    }

    /**
     * Apply the resolution rule to the raw rows.
     *
     * <p>Rebuilt from rows rather than cached as {@link NamingPolicy} objects because a policy holds
     * a compiled {@link java.util.regex.Pattern}, which does not round-trip through JSON. Compiling
     * once per resolution is the point at which it is cheap; once per item is what the plan forbids.
     */
    private static ResolvedPolicies assemble(List<NamingPolicyRepository.NamingPolicyRow> rows) {
        NamingPolicy tenantPolicy = NamingPolicy.shippedDefault();
        Map<Long, NamingPolicy> byDataSet = new HashMap<>();
        for (NamingPolicyRepository.NamingPolicyRow row : rows) {
            NamingPolicy policy = NamingPolicy.fromMetadata(row.policyId(), row.externalId(), row.metadata());
            if (row.dataSetId() == null) {
                tenantPolicy = policy;
            } else {
                byDataSet.put(row.dataSetId(), policy);
            }
        }
        return new ResolvedPolicies(tenantPolicy, byDataSet);
    }

    private void writeCache(String cacheKey, CachedPolicies entry) {
        try {
            valkeyService.setString(cacheKey, jsonMapper.writeValueAsString(entry), cacheTtlSeconds);
        } catch (Exception e) {
            log.warn("Naming-policy cache write failed for {}: {}", cacheKey, e.getMessage());
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

    /** The tenant's raw policy rows plus the generation they were read at. */
    record CachedPolicies(List<NamingPolicyRepository.NamingPolicyRow> rows, long generation) {}

    /**
     * Bump the tenant's policy generation, invalidating the cached policy set.
     *
     * <p>Call from every path that creates, updates or deletes a policy. A single {@code INCR}, so
     * it is cheap and safe under concurrent callers across api instances.
     */
    public void invalidate() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        try {
            long generation = valkeyService.increment(GENERATION_KEY_PREFIX + tenantId, 1);
            log.debug("Bumped naming-policy generation for tenant {} to {}", tenantId, generation);
        } catch (Exception e) {
            // Losing a bump means an edited policy stays stale until the TTL expires. Loud, because
            // a policy someone changed and that did not take effect is a correctness problem.
            log.error("Could not bump naming-policy generation for tenant {}; the cached policy set "
                    + "stays stale for up to {}s", tenantId, cacheTtlSeconds, e);
        }
    }

    /**
     * The tenant's policies, with the resolution rule applied.
     *
     * @param tenantPolicy the tenant-wide rule, or the shipped default when none is configured
     * @param byDataSet    per-data-set overrides
     */
    public record ResolvedPolicies(NamingPolicy tenantPolicy, Map<Long, NamingPolicy> byDataSet) {

        /**
         * Most specific wins. A null {@code dataSetId} means the item has no data set, which can
         * only be governed tenant-wide.
         */
        public NamingPolicy forDataSet(Long dataSetId) {
            if (dataSetId == null) {
                return tenantPolicy;
            }
            return byDataSet.getOrDefault(dataSetId, tenantPolicy);
        }
    }
}
