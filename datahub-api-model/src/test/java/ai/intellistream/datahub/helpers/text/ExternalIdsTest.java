// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the two derived forms of an external id. Both are load-bearing: {@link ExternalIds#hash} is
 * what makes uniqueness and lookup case-insensitive while storage stays verbatim, and
 * {@link ExternalIds#fold} is what the near-duplicate guard compares on.
 */
class ExternalIdsTest {

    @Test
    void hashIsCaseInsensitive() {
        // This is the whole uniqueness rule: same hash → the existing unique index rejects the
        // second one, and a lookup for either finds the one row.
        assertEquals(ExternalIds.hash("COM-99-PT-1034"), ExternalIds.hash("com-99-pt-1034"));
        assertEquals(ExternalIds.hash("Pump_A_01"), ExternalIds.hash("pump_a_01"));
    }

    @Test
    void hashDistinguishesSeparators() {
        // Case folds, separators do not. `pump-a-01` and `pump_a_01` are genuinely different
        // identifiers under the uniqueness rule — catching them is the near-duplicate guard's job,
        // not the hash's, because the guard can be configured to warn rather than reject.
        assertNotEquals(ExternalIds.hash("pump-a-01"), ExternalIds.hash("pump_a_01"));
    }

    @Test
    void hashIsUnchangedForExistingLowercaseIds() {
        // Why no backfill is needed: the validator only ever accepted [a-z0-9_], so every stored
        // external id is already lowercase and its stored hash is already correct.
        String legacy = "basement_airthings_radon1_bigint";
        assertEquals(ExternalIds.hash(legacy), ExternalIds.hash(legacy.toLowerCase()));
    }

    @Test
    void foldCollapsesSeparatorsAndCase() {
        assertEquals("pump_a_01", ExternalIds.fold("pump-a-01"));
        assertEquals("pump_a_01", ExternalIds.fold("PUMP.A.01"));
        assertEquals("pump_a_01", ExternalIds.fold("pump_a_01"));
        assertEquals("pump_a_01", ExternalIds.fold("Pump:A+01"));
        assertEquals("_k1_m3_b02", ExternalIds.fold("=K1-M3+B02"));
    }

    @Test
    void foldMatchesTheSqlFunctionalIndexExpression() {
        // The index is lower(translate(external_id, '-.:+=', '_____')). If this drifts from that
        // expression the guard silently stops using the index and every batch write degrades to a
        // sequential scan — so assert the exact character set it folds.
        assertEquals("_____", ExternalIds.fold("-.:+="));
    }

    @Test
    void foldLeavesUnrelatedCharactersAlone() {
        assertEquals("abc123", ExternalIds.fold("ABC123"));
    }
}
