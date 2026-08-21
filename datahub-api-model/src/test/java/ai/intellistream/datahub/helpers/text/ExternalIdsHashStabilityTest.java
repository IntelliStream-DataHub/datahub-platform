// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@code external_id_hash} to exact values.
 *
 * <p>This one is stored on every node, every unit, every file and every subscription, and it carries
 * a UNIQUE index. Changing how it is computed does two things at once, both silent: lookups stop
 * finding rows that are there, and the uniqueness guarantee stops holding because a new write hashes
 * differently from the old one it should have collided with.
 *
 * <p>The label hash drifted exactly that way — see {@code TypeLabelHashStabilityTest} — and went
 * unnoticed for weeks because nothing pinned it. External ids have always been XXH3 over the
 * lowercased value; these assertions are what keeps that true by decision rather than by luck.
 *
 * <p>If one fails, the fix is a migration that rehashes the affected columns, not a new expected
 * value here.
 */
class ExternalIdsHashStabilityTest {

    @ParameterizedTest
    @CsvSource({
            "sap_work_orders,   3461965448821005242",
            "COM-99-PT-1034,   -8361149411380936381",   // an ISA-5.1 plant tag
            "=K1-M3+B02,       -5508403285662376478",   // an IEC 81346 reference designation
            "a,                 970730967732026843"})
    void externalIdsHashToTheirPinnedValues(String externalId, long expected) {
        assertEquals(expected, ExternalIds.hash(externalId),
                "the stored external_id_hash for '" + externalId + "' changed; every row with one "
                        + "needs a migration, and the UNIQUE index stops meaning what it meant");
    }

    /**
     * The lowercasing is part of the stored value, not a lookup convenience — it is what makes the
     * unique index case-insensitive. Folding it away would let two spellings of one id coexist.
     */
    @Test
    void caseIsFoldedIntoTheHashItself() {
        assertEquals(ExternalIds.hash("COM-99-PT-1034"), ExternalIds.hash("com-99-pt-1034"));
        assertEquals(ExternalIds.hash("Pump_A_01"), ExternalIds.hash("pump_a_01"));
    }

    /**
     * {@code fold} backs the near-duplicate guard and must match the functional index
     * {@code node_external_id_folded_idx}, which does
     * {@code lower(translate(external_id, '-.:+=', '_____'))}. Drift here silently degrades that
     * index to a sequential scan on every batch write.
     */
    @ParameterizedTest
    @CsvSource({
            "sap_work_orders, sap_work_orders",
            "COM-99-PT-1034,  com_99_pt_1034",
            "=K1-M3+B02,      _k1_m3_b02",
            "PUMP.A.01,       pump_a_01"})
    void foldingMatchesTheFunctionalIndex(String externalId, String expected) {
        assertEquals(expected, ExternalIds.fold(externalId));
    }
}
