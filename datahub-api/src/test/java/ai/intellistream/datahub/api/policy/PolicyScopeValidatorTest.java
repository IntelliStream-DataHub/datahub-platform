// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.PolicyType;
import ai.intellistream.datahub.models.policy.NamingPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attachment validation, which is what stops {@code PolicyScope} being documentation, plus the
 * save-time check on a supplied regex.
 */
class PolicyScopeValidatorTest {

    @Test
    void aPerDataSetPolicyMustBeAttachedToOne() {
        Policy policy = new Policy();
        policy.setType(PolicyType.IS_WRITE_PROTECTED);
        policy.setDataSetId(null);

        // BadRequestException carries its detail in the error envelope rather than the Throwable
        // message, so assert on the envelope — see theRejectionNamesTheOffendingField below.
        assertThatThrownBy(() -> PolicyScopeValidator.validate(policy))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void namingCanBeAttachedToADataSetOrLeftTenantWide() {
        Policy attached = new Policy();
        attached.setType(PolicyType.NAMING_CONVENTION);
        attached.setDataSetId(5L);
        assertThatCode(() -> PolicyScopeValidator.validate(attached)).doesNotThrowAnyException();

        Policy tenantWide = new Policy();
        tenantWide.setType(PolicyType.NAMING_CONVENTION);
        assertThatCode(() -> PolicyScopeValidator.validate(tenantWide)).doesNotThrowAnyException();
    }

    @Test
    void aNamingPolicyDeclaringThePatternPresetMustSupplyAUsableOne() {
        // Caught here, at save time, where the error reaches the person who typed it — rather than
        // during ingest, where it reaches an integration that cannot fix it.
        Policy policy = namingPolicy(Map.of(
                NamingPolicy.KEY_PRESET, "pattern",
                NamingPolicy.KEY_PATTERN, "[unclosed"));

        assertThatThrownBy(() -> PolicyScopeValidator.validate(policy))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aMissingPatternIsRejectedTooRatherThanSilentlyConstrainingNothing() {
        Policy policy = namingPolicy(Map.of(NamingPolicy.KEY_PRESET, "pattern"));

        assertThatThrownBy(() -> PolicyScopeValidator.validate(policy))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anOverlongPatternIsRejected() {
        // A caller-supplied regex runs on the write path, so its size is bounded.
        String huge = "a".repeat(NamingPolicy.MAX_PATTERN_LENGTH + 1);
        Policy policy = namingPolicy(Map.of(
                NamingPolicy.KEY_PRESET, "pattern",
                NamingPolicy.KEY_PATTERN, huge));

        assertThatThrownBy(() -> PolicyScopeValidator.validate(policy))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aValidPatternPasses() {
        Policy policy = namingPolicy(Map.of(
                NamingPolicy.KEY_PRESET, "pattern",
                NamingPolicy.KEY_PATTERN, "[A-Z]{2}-\\d{4}"));

        assertThatCode(() -> PolicyScopeValidator.validate(policy)).doesNotThrowAnyException();
    }

    @Test
    void anotherPolicyKindsMetadataIsNotSecondGuessed() {
        // Other kinds carry their own config in the same map; a "pattern" key there means something
        // else and must not be validated as a naming regex.
        Policy policy = new Policy();
        policy.setType(PolicyType.MASKING_POLICY);
        policy.setDataSetId(5L);
        Map<String, String> metadata = new HashMap<>();
        metadata.put(NamingPolicy.KEY_KIND, "masking");
        metadata.put(NamingPolicy.KEY_PRESET, "pattern");
        metadata.put(NamingPolicy.KEY_PATTERN, "[unclosed");
        policy.setMetadata(metadata);

        assertThatCode(() -> PolicyScopeValidator.validate(policy)).doesNotThrowAnyException();
    }

    @Test
    void aPolicyWithNoTypeIsNotScopeChecked() {
        // The console creates policies from a template before a type is chosen; that is not the
        // moment to demand one.
        Policy policy = new Policy();
        policy.setDataSetId(5L);
        assertThatCode(() -> PolicyScopeValidator.validate(policy)).doesNotThrowAnyException();
    }

    @Test
    void theRejectionNamesTheOffendingField() {
        Policy policy = new Policy();
        policy.setType(PolicyType.IS_WRITE_PROTECTED);

        BadRequestException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                BadRequestException.class, () -> PolicyScopeValidator.validate(policy));

        assertThat(thrown.getError().getError().getMessage()).contains("IS_WRITE_PROTECTED");
        assertThat(thrown.getError().getError().getFields()).isNotEmpty();
    }

    private static Policy namingPolicy(Map<String, String> extraMetadata) {
        Policy policy = new Policy();
        policy.setType(PolicyType.NAMING_CONVENTION);
        Map<String, String> metadata = new HashMap<>(extraMetadata);
        metadata.put(NamingPolicy.KEY_KIND, NamingPolicy.KIND_NAMING);
        policy.setMetadata(metadata);
        return policy;
    }
}
