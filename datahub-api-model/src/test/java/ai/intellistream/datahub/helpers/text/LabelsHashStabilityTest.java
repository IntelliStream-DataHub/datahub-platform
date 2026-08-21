// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the label hash to exact values, because changing it silently orphans every stored row.
 *
 * <p>This is the test that would have caught {@code 5c22b485}. That commit fixed 409s on duplicate
 * label names and, in passing, deleted the line writing the hash with XXH64 — leaving
 * {@code Label.setName}'s XXH3 as the only writer. Nothing failed. Labels created before it kept
 * hashes no current code could reproduce, so filtering by them returned empty while every read still
 * showed the label, and it stayed that way for weeks.
 *
 * <p>A derived value that is <em>stored</em> is a contract with the rows already written, not an
 * implementation detail. Changing it means writing a backfill in the same commit; these assertions
 * make that a decision rather than an accident. If one fails, the fix is a migration, not a new
 * expected value.
 */
class LabelsHashStabilityTest {

    @Test
    void theLabelHashIsXx3OverTheCanonicalName() {
        // Verified against a production row: label id 1, name DATASET, written before 5c22b485
        // carried 455134091631135939 — the XXH64 value. This is the XXH3 one, and V37 rewrites to it.
        assertEquals(7048531376555374514L, Labels.hash("DATASET"));
        assertEquals(-1121691946287719724L, Labels.hash("TIMESERIES"));
    }

    @Test
    void canonicalisationIsPartOfTheHash() {
        // The hash is over the canonical form, so how a caller spelled it cannot change the value.
        assertEquals(Labels.hash("PUMP_A"), Labels.hash("pump a"));
        assertEquals(Labels.hash("PUMP_A"), Labels.hash("Pump-A"));
    }

    @Test
    void theOldXxh64ValueIsNotWhatWeProduce() {
        // Nailing the regression direction: if this ever passes, the algorithm went backwards.
        assertEquals(false, Labels.hash("DATASET") == 455134091631135939L);
    }
}
