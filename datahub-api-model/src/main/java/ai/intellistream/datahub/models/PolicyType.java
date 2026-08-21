// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.models.policy.PolicyScope;
import net.openhft.hashing.LongHashFunction;

import java.util.Arrays;
import java.util.Locale;

public enum PolicyType {

    /** Restricts data access or actions for security compliance. */
    SECURITY_POLICY(PolicyScope.DATASET_ONLY),

    /** Defines encryption requirements for stored or transmitted data. */
    ENCRYPTION_POLICY(PolicyScope.DATASET_ONLY),

    /** Hides or obfuscates sensitive fields such as names or identifiers. */
    MASKING_POLICY(PolicyScope.DATASET_ONLY),

    /** Marks a dataset as read-only; blocks writes except delete. */
    IS_WRITE_PROTECTED(PolicyScope.DATASET_ONLY),

    /** Restricts read access to authorized users only. */
    IS_READ_PROTECTED(PolicyScope.DATASET_ONLY),

    /** Indicates datasets that must meet specific compliance or process requirements. */
    HAS_REQUIREMENT(PolicyScope.DATASET_ONLY),

    /**
     * The external-id naming convention enforced at write time.
     *
     * <p>Appended rather than inserted: {@link #fromId(int)} resolves by ordinal, so reordering this
     * enum would silently reinterpret every stored policy type.
     *
     * <p>Scoped {@link PolicyScope#TENANT_WITH_DATASET_OVERRIDE} because a data-set-scoped naming
     * rule cannot govern the naming of the data set itself.
     */
    NAMING_CONVENTION(PolicyScope.TENANT_WITH_DATASET_OVERRIDE);

    private final PolicyScope scope;

    PolicyType(PolicyScope scope) {
        this.scope = scope;
    }

    /** Where this kind of policy may be attached. See {@link PolicyScope}. */
    public PolicyScope getScope() {
        return scope;
    }

    /** Whether attaching this policy directly to a data set is allowed. */
    public boolean canAttachToDataSet() {
        return scope != PolicyScope.TENANT_ONLY;
    }

    /** Whether this policy may exist tenant-wide, unattached to any data set. */
    public boolean canApplyTenantWide() {
        return scope != PolicyScope.DATASET_ONLY;
    }

    public static PolicyType fromId(int number){
        return Arrays.stream(values())
                .filter( it -> it.ordinal() == number)
                .findFirst()
                .orElse(null);
    }

    public long getExternalId() {
        // Equivalent to the former IdGenerator.xxHash(...); inlined to keep this
        // contract module free of the IdGenerator utility (UUID/commons-codec deps).
        return LongHashFunction.xx3().hashChars("policy_" + this.name().toLowerCase(Locale.ROOT));
    }
}
