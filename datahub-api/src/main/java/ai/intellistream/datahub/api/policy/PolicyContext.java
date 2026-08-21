// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.models.policy.NamingPolicy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * What an evaluator needs to judge a batch: the tenant, who is asking, and the policy in force for
 * each data set the batch touches.
 *
 * <p>Policies are resolved <strong>lazily and memoised</strong>. A 1000-item batch landing in one
 * data set does one resolution, not a thousand — and since resolution can reach the database and
 * the cache, a per-item lookup on the write path is exactly the kind of cost that silently
 * regresses. The memo is per-context, so it lives for one batch and no longer.
 *
 * <p>Not thread-safe, and does not need to be: a context belongs to one request.
 */
public final class PolicyContext {

    private final String tenantId;
    private final String raisedBy;
    private final Function<Long, NamingPolicy> namingResolver;
    private final Map<Long, NamingPolicy> resolved = new HashMap<>();

    public PolicyContext(String tenantId, String raisedBy, Function<Long, NamingPolicy> namingResolver) {
        this.tenantId = tenantId;
        this.raisedBy = raisedBy;
        this.namingResolver = namingResolver;
    }

    public String tenantId() {
        return tenantId;
    }

    /** The JWT {@code sub} of whoever is writing, recorded on findings so a steward can trace one back. */
    public String raisedBy() {
        return raisedBy;
    }

    /**
     * The naming policy governing a data set. A null {@code dataSetId} means "no data set", which
     * resolves to the tenant policy — {@link HashMap} permits the null key, which is why it is used
     * here rather than a {@code Map.of}-style map.
     */
    public NamingPolicy namingPolicyFor(Long dataSetId) {
        return resolved.computeIfAbsent(dataSetId, namingResolver);
    }

    /** How many distinct resolutions this batch actually performed. Asserted by the performance test. */
    public int resolutionCount() {
        return resolved.size();
    }
}
