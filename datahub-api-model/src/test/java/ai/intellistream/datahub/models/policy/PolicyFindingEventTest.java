// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The finding-as-event encoding.
 *
 * <p>These two derivations are the load-bearing part of it. The external id is what makes
 * re-evaluating an entity land on the finding already there instead of appending a second one — it
 * replaced a database unique constraint, so it has to be exactly as stable as that constraint was.
 * The source has to fit a validated column, because a finding that fails validation would take the
 * caller's write down with it.
 *
 * <p>Plain JUnit assertions rather than AssertJ: this module's test classpath is deliberately as
 * lean as its runtime one.
 */
class PolicyFindingEventTest {

    @Test
    void externalIdIsStableForTheSameEntityAndPolicy() {
        assertEquals("policy_finding_naming_default_42",
                PolicyFindingEvent.externalIdFor("naming_default", 42L));
        assertEquals(PolicyFindingEvent.externalIdFor("naming_default", 42L),
                PolicyFindingEvent.externalIdFor("naming_default", 42L));
    }

    @Test
    void externalIdSeparatesEntitiesAndPolicies() {
        assertNotEquals(PolicyFindingEvent.externalIdFor("naming_default", 42L),
                PolicyFindingEvent.externalIdFor("naming_default", 43L));
        assertNotEquals(PolicyFindingEvent.externalIdFor("naming_default", 42L),
                PolicyFindingEvent.externalIdFor("naming_strict", 42L));
    }

    /**
     * The offending value is the entity's external id, and a steward fixing a complaint renames the
     * entity. If the finding's identity moved with that rename the queue would be left holding a
     * complaint about a value that no longer exists, and the fix would raise a second finding rather
     * than settling the first.
     */
    @Test
    void externalIdDoesNotDependOnTheEntitysOwnExternalId() {
        assertEquals("policy_finding_naming_default_42",
                PolicyFindingEvent.externalIdFor("naming_default", 42L));
    }

    @Test
    void sourceNamesThePolicyThatFired() {
        assertEquals("datahub_policy_naming_default", PolicyFindingEvent.sourceFor("naming_default"));
    }

    @Test
    void sourceIsTruncatedRatherThanAllowedToFailValidation() {
        String source = PolicyFindingEvent.sourceFor("x".repeat(500));

        // 128 is the @Size cap on EventModel.source. Exceeding it fails bean validation, and a
        // finding must never be able to fail the write it is a note about.
        assertEquals(128, source.length());
        assertTrue(source.startsWith(PolicyFindingEvent.SOURCE_PREFIX), source);
    }
}
