// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.models.PolicyType;
import ai.intellistream.datahub.models.policy.NamingPolicy;
import ai.intellistream.datahub.models.policy.NamingPreset;
import ai.intellistream.datahub.models.policy.PolicyMode;
import ai.intellistream.datahub.models.policy.PolicyScope;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a policy is chosen for a given data set, and what the shipped defaults are when none is
 * configured.
 */
class NamingPolicyResolutionTest {

    private static final NamingPolicy TENANT =
            new NamingPolicy(1L, "tenant_rule", NamingPreset.SNAKE_CASE, null, PolicyMode.REJECT, PolicyMode.REJECT);
    private static final NamingPolicy DATASET =
            new NamingPolicy(2L, "dataset_rule", NamingPreset.VERBATIM_TAG, null, PolicyMode.WARN, PolicyMode.WARN);

    @Test
    void aDataSetPolicyReplacesTheTenantPolicyRatherThanMergingWithIt() {
        // Most specific wins, whole. A partially-overridden naming rule — this data set's preset but
        // the tenant's near-duplicate mode — is not something anyone can reason about.
        var resolved = new NamingPolicyResolver.ResolvedPolicies(TENANT, Map.of(5L, DATASET));

        NamingPolicy forOverridden = resolved.forDataSet(5L);
        assertThat(forOverridden.preset()).isEqualTo(NamingPreset.VERBATIM_TAG);
        assertThat(forOverridden.mode()).isEqualTo(PolicyMode.WARN);
        assertThat(forOverridden.nearDuplicateMode()).isEqualTo(PolicyMode.WARN);
        assertThat(forOverridden.policyExternalId()).isEqualTo("dataset_rule");
    }

    @Test
    void aDataSetWithNoOverrideFallsBackToTheTenantPolicy() {
        var resolved = new NamingPolicyResolver.ResolvedPolicies(TENANT, Map.of(5L, DATASET));
        assertThat(resolved.forDataSet(9L).policyExternalId()).isEqualTo("tenant_rule");
    }

    @Test
    void anItemWithNoDataSetIsGovernedTenantWide() {
        // A data-set-scoped rule cannot govern something that is in no data set — including the
        // naming of a data set itself, which is why naming is tenant-rooted with an override rather
        // than per-data-set with a fallback.
        var resolved = new NamingPolicyResolver.ResolvedPolicies(TENANT, Map.of(5L, DATASET));
        assertThat(resolved.forDataSet(null).policyExternalId()).isEqualTo("tenant_rule");
    }

    @Test
    void withNoPolicyConfiguredTheShippedDefaultApplies() {
        var resolved = new NamingPolicyResolver.ResolvedPolicies(NamingPolicy.shippedDefault(), Map.of());
        NamingPolicy policy = resolved.forDataSet(5L);

        assertThat(policy.preset()).isEqualTo(NamingPreset.QUALIFIED_TAG);
        // Both rules ship warning, so the default never refuses a write: each is a judgement the
        // platform cannot make for the caller, and both land in the steward's queue instead.
        assertThat(policy.mode()).isEqualTo(PolicyMode.WARN);
        assertThat(policy.nearDuplicateMode()).isEqualTo(PolicyMode.WARN);
    }

    // --- reading configuration off a policy node ----------------------------------------------

    @Test
    void metadataIsReadIntoAPolicy() {
        NamingPolicy policy = NamingPolicy.fromMetadata(3L, "house_rule", Map.of(
                NamingPolicy.KEY_KIND, NamingPolicy.KIND_NAMING,
                NamingPolicy.KEY_PRESET, "snake_case",
                NamingPolicy.KEY_MODE, "warn",
                NamingPolicy.KEY_NEAR_DUPLICATE_MODE, "reject"));

        assertThat(policy.preset()).isEqualTo(NamingPreset.SNAKE_CASE);
        assertThat(policy.mode()).isEqualTo(PolicyMode.WARN);
        assertThat(policy.nearDuplicateMode()).isEqualTo(PolicyMode.REJECT);
    }

    @Test
    void anUnusableRegexDegradesToVerbatimRatherThanFailingEveryWrite() {
        // Lenient on the write path on purpose: a malformed pattern must not take the tenant's
        // ingest down. PolicyScopeValidator catches it at save time, where the person who typed it
        // can see the error.
        NamingPolicy policy = NamingPolicy.fromMetadata(3L, "house_rule", Map.of(
                NamingPolicy.KEY_PRESET, "pattern",
                NamingPolicy.KEY_PATTERN, "[unclosed"));

        assertThat(policy.preset()).isEqualTo(NamingPreset.VERBATIM_TAG);
        assertThat(policy.matchesPreset("anything-at-all")).isTrue();
    }

    @Test
    void unknownValuesFallBackToTheShippedDefaults() {
        NamingPolicy policy = NamingPolicy.fromMetadata(3L, "house_rule", Map.of(
                NamingPolicy.KEY_PRESET, "not_a_preset",
                NamingPolicy.KEY_MODE, "maybe"));

        // An unreadable preset falls back to the shipped one, so "the default" means the same thing
        // whether or not a policy node exists. An unreadable mode does not: a node was configured
        // deliberately, and the safe reading of an unparseable severity is the stricter one.
        assertThat(policy.preset()).isEqualTo(NamingPreset.QUALIFIED_TAG);
        assertThat(policy.mode()).isEqualTo(PolicyMode.REJECT);
    }

    // --- scope declared on the type ------------------------------------------------------------

    @Test
    void namingIsTenantRootedWithADataSetOverride() {
        assertThat(PolicyType.NAMING_CONVENTION.getScope())
                .isEqualTo(PolicyScope.TENANT_WITH_DATASET_OVERRIDE);
        assertThat(PolicyType.NAMING_CONVENTION.canApplyTenantWide()).isTrue();
        assertThat(PolicyType.NAMING_CONVENTION.canAttachToDataSet()).isTrue();
    }

    @Test
    void writeProtectionIsPerDataSetOnly() {
        assertThat(PolicyType.IS_WRITE_PROTECTED.canApplyTenantWide()).isFalse();
    }

    @Test
    void namingConventionIsAppendedSoStoredOrdinalsDoNotShift() {
        // fromId() resolves by ordinal, so reordering this enum would silently reinterpret every
        // stored policy type.
        assertThat(PolicyType.NAMING_CONVENTION.ordinal())
                .isEqualTo(PolicyType.values().length - 1);
        assertThat(PolicyType.fromId(PolicyType.IS_WRITE_PROTECTED.ordinal()))
                .isEqualTo(PolicyType.IS_WRITE_PROTECTED);
    }
}
