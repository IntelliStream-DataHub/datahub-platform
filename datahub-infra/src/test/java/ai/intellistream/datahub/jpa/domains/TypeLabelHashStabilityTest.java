// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.helpers.text.Labels;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the hash of every type-label to an exact value.
 *
 * <p>These are the labels most likely to be old, because every node gets one at creation and the
 * rows date from the first tenant ever provisioned. That is what made the drift expensive: commit
 * {@code 5c22b485} (2026-07-20) fixed 409s on duplicate label names and, in passing, removed the
 * line writing {@code label.hash} with XXH64, leaving {@code Label.setName}'s XXH3 as the only
 * writer. Nothing failed. Every label written before that date kept a hash no code could reproduce,
 * so filtering by {@code DATASET} returned an empty result while every read still showed the label
 * — for weeks, until someone filtered by one and looked closely.
 *
 * <p>A derived value that is <em>stored</em> is a contract with the rows already written, not an
 * implementation detail. If one of these fails, the answer is a migration in the same commit — see
 * {@code V37__rehash_labels_from_xx64_to_xx3} — not a new expected value.
 */
class TypeLabelHashStabilityTest {

    /** Every type-label and the hash the platform must keep producing for it. */
    private static final Map<String, Long> PINNED = Map.of(
            TypeLabels.ASSET,       5351070799090645991L,
            TypeLabels.DATASET,     7048531376555374514L,
            TypeLabels.FUNCTION,   -1456073011649991206L,
            TypeLabels.POLICY,      8043948517852117702L,
            TypeLabels.TIMESERIES, -1121691946287719724L);

    @Test
    void everyTypeLabelHashesToItsPinnedValue() {
        PINNED.forEach((label, expected) -> assertEquals(expected, Labels.hash(label),
                "the stored hash for " + label + " changed; existing rows need a migration"));
    }

    /**
     * A new type-label must arrive with its hash pinned. Without this the map silently stops
     * covering the set it is supposed to cover, which is how a guard quietly becomes decorative.
     */
    @Test
    void everyTypeLabelIsPinned() {
        assertEquals(TypeLabels.ALL, PINNED.keySet(),
                "TypeLabels.ALL and the pinned hashes have diverged");
    }

    /** The value the old algorithm produced, named so a revert is recognisable rather than puzzling. */
    @Test
    void theSupersededXxh64ValueIsNotProduced() {
        assertTrue(Labels.hash(TypeLabels.DATASET) != 455134091631135939L,
                "this is the XXH64 hash V37 exists to replace");
    }
}
