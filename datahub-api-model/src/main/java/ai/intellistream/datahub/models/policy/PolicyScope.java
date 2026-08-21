// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

/**
 * Where a policy may be attached.
 *
 * <p>Declared on {@code PolicyType}, not on an individual policy: whether a policy <em>can</em> be
 * attached per data set follows from what the policy does, not from how someone configured it. It
 * also disambiguates a nullable {@code Policy.dataSetId}, where null could not be told apart from
 * "a draft that has not been attached to anything yet".
 *
 * <p>The enum only earns its keep if attachment is validated against it — rejecting a
 * {@link #TENANT_ONLY} policy attached to a data set, and a {@link #DATASET_ONLY} one that is not.
 * Without that check this is documentation.
 */
public enum PolicyScope {

    /** Tenant-wide only, e.g. an audit-retention rule. Attaching it to a data set is an error. */
    TENANT_ONLY,

    /** Per data set only, e.g. {@code IS_WRITE_PROTECTED}. Attaching it tenant-wide is an error. */
    DATASET_ONLY,

    /**
     * A tenant default that a data set may override.
     *
     * <p>Naming resolves this way rather than "per data set with a tenant fallback", and the
     * difference is not cosmetic: a data-set-scoped naming rule cannot govern the naming of the data
     * set itself. The rule has to be rooted at the tenant for there to be a rule in force when the
     * thing being named is the data set.
     */
    TENANT_WITH_DATASET_OVERRIDE
}
