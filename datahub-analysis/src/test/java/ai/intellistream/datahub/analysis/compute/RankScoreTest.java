// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.compute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence-weighted composite rank ({@link AnalysisComputer#compositeRank}). Asserts orderings
 * rather than exact values, so the weights can be tuned without churning the test — except the one
 * property that must hold by design: an insignificant whitened test and a missing one collapse to the
 * same raw-only base.
 */
class RankScoreTest {

    private static final double SIG = 0.001;   // significant whitened p-value
    private static final double INSIG = 0.5;   // insignificant whitened p-value

    @Test
    void genuineWhitenedEvidenceOutranksAStrongerRawOnlyCorrelation() {
        double genuine = AnalysisComputer.compositeRank(0.70, 0.80, SIG, false, null, true);
        double rawOnly = AnalysisComputer.compositeRank(0.95, null, null, false, null, true);
        assertTrue(genuine > rawOnly, "significant whitened (0.80) should beat a stronger raw-only (0.95): "
                + genuine + " vs " + rawOnly);
    }

    @Test
    void insignificantWhitenedFallsBackToTheSameBaseAsRawOnly() {
        // The property from the review: testing whitening and finding nothing must NOT score higher
        // (or lower) than simply not having a whitened test — both fall back to 0.5·|raw|.
        double whitenedInsignificant = AnalysisComputer.compositeRank(0.90, 0.70, INSIG, false, null, true);
        double rawOnlySameRaw = AnalysisComputer.compositeRank(0.90, null, null, false, null, true);
        assertEquals(rawOnlySameRaw, whitenedInsignificant, 1e-9,
                "whitened-insignificant and raw-only with the same raw must score identically");
    }

    @Test
    void aRawCorrelationWhiteningRejectsIsNotRewardedForItsWhitenedNumber() {
        // High raw, high whitened magnitude, but whitening says independent (co-trend/spurious) →
        // scored only on the discounted raw, well below a genuine pair of much lower magnitude.
        double spurious = AnalysisComputer.compositeRank(0.95, 0.90, INSIG, false, null, true);
        double genuineModest = AnalysisComputer.compositeRank(0.30, 0.55, SIG, false, null, true);
        assertTrue(genuineModest > spurious,
                "a genuine 0.55 whitened link should outrank a spurious 0.95/0.90 one: "
                        + genuineModest + " vs " + spurious);
    }

    @Test
    void unstableIsDownweightedVersusStable() {
        double stable = AnalysisComputer.compositeRank(null, 0.60, SIG, false, null, true);
        double unstable = AnalysisComputer.compositeRank(null, 0.60, SIG, false, null, false);
        double untested = AnalysisComputer.compositeRank(null, 0.60, SIG, false, null, null);
        assertTrue(stable > untested && untested > unstable,
                "stable > untested > unstable: " + stable + " / " + untested + " / " + unstable);
    }

    @Test
    void cointegrationAndCoherenceCorroborateAGenuinePair() {
        double bare = AnalysisComputer.compositeRank(null, 0.60, SIG, false, null, true);
        double cointegrated = AnalysisComputer.compositeRank(null, 0.60, SIG, true, null, true);
        double coherent = AnalysisComputer.compositeRank(null, 0.60, SIG, false, 0.9, true);
        assertTrue(cointegrated > bare, "cointegration should lift the score: " + cointegrated + " vs " + bare);
        assertTrue(coherent > bare, "a strong coherence peak should lift the score: " + coherent + " vs " + bare);
    }

    @Test
    void noEvidenceScoresZero_andEverythingStaysInUnitRange() {
        assertEquals(0.0, AnalysisComputer.compositeRank(null, null, null, null, null, null));
        double maxed = AnalysisComputer.compositeRank(1.0, 1.0, 0.0, true, 1.0, true);
        assertTrue(maxed <= 1.0 && maxed > 0.9, "a maxed-out pair should be near but not exceed 1.0: " + maxed);
    }
}
