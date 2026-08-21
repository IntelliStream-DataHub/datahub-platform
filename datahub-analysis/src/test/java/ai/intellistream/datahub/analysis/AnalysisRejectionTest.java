// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The negative half of the engine's job — and arguably the more important half for a
 * "what's worth investigating?" tool: <em>unrelated</em> series must be rejected, so users aren't
 * sent chasing spurious correlations. These are seeded, realization-based checks that measure the
 * false-positive <em>rate</em> across many independent draws rather than trusting a single lucky seed.
 *
 * <p>Companion to {@link AnalysisEngineTest} (which proves each statistic catches its intended
 * relationship). Here we prove each statistic (and the suite together) rejects the ones it should.
 */
class AnalysisRejectionTest {

    private static final int TRIALS = 40;
    private static final int N = 800;
    private static final int MAX_LAG = 20;
    private static final int WINDOWS = 8;
    private static final int BUCKET = 60;

    /**
     * The classic spurious-regression trap: two <em>independent</em> random walks. Raw correlation is
     * routinely fooled (each walk drifts, so they look co-trended), but cointegration and ARIMA
     * pre-whitening reject the large majority — their false-positive rate sits near the 5% test size.
     */
    @Test
    void independentRandomWalks_foolRawCorrelation_butAreRejectedByCointegrationAndWhitening() {
        Random rnd = new Random(20260707L);
        int rawFooled = 0;
        int cointegrated = 0;
        int whitenedDependent = 0;
        for (int t = 0; t < TRIALS; t++) {
            double[] x = randomWalk(N, rnd);
            double[] y = randomWalk(N, rnd); // independent of x
            if (Math.abs(CrossCorrelation.ccf(x, y, MAX_LAG).bestR()) > 0.5) {
                rawFooled++;
            }
            if (Cointegration.test(x, y).cointegrated()) {
                cointegrated++;
            }
            if (whitenedPValue(x, y) < 0.05) {
                whitenedDependent++;
            }
        }
        // Raw cross-correlation is genuinely unreliable here — that's WHY the other statistics exist.
        assertTrue(rawFooled >= TRIALS / 4,
                "raw CCF should look spuriously strong on many independent walks, was " + rawFooled + "/" + TRIALS);
        // The robust statistics keep the false-positive rate low.
        assertTrue(cointegrated <= TRIALS / 5,
                "cointegration should reject most independent walks, flagged " + cointegrated + "/" + TRIALS);
        assertTrue(whitenedDependent <= TRIALS / 5,
                "whitened CCF should reject most independent walks, flagged " + whitenedDependent + "/" + TRIALS);
    }

    /**
     * Co-trending without coupling: two series riding a shared deterministic trend but with
     * independent innovations. Raw correlation is near 1 (the trend dominates), yet whitening —
     * which differences the trend away — correctly reports independence. This is exactly the
     * "does whitening discard co-trends?" case: yes, deliberately.
     */
    @Test
    void sharedTrendWithIndependentInnovations_isRejectedByWhitening() {
        Random rnd = new Random(1234L);
        double[] x = new double[N];
        double[] y = new double[N];
        for (int i = 0; i < N; i++) {
            double trend = 0.05 * i;            // shared deterministic ramp
            x[i] = trend + rnd.nextGaussian();  // independent innovations
            y[i] = trend + rnd.nextGaussian();
        }
        double rawR = CrossCorrelation.ccf(x, y, MAX_LAG).bestR();
        assertTrue(rawR > 0.9, "shared trend should make raw correlation high, was " + rawR);
        double p = whitenedPValue(x, y);
        assertTrue(p > 0.05, "co-trend with independent innovations should whiten to independence, p=" + p);
    }

    /**
     * Two independent white-noise series are rejected on every axis now that the pretest guards are in
     * place: raw correlation is tiny; the data-driven differencing keeps {@code d=0} so whitening no
     * longer over-differences (the ~40%-false-positive bug) and calls them independent; and because
     * both test stationary, the compute gates the cointegration test off entirely (no spurious
     * "lasting link" — its residual would otherwise be trivially stationary).
     */
    @Test
    void independentWhiteNoise_isRejectedByRawCorrelationWhiteningAndCointegrationGate() {
        Random rnd = new Random(99L);
        int lowRaw = 0;
        int whitenedIndependent = 0;
        int bothStationary = 0;
        double sumAbsR = 0;
        for (int t = 0; t < TRIALS; t++) {
            double[] x = gaussianSeries(N, rnd);
            double[] y = gaussianSeries(N, rnd);
            double r = Math.abs(CrossCorrelation.pearson(x, y));
            sumAbsR += r;
            if (r < 0.15) {
                lowRaw++;
            }
            if (whitenedPValue(x, y) >= 0.05) {
                whitenedIndependent++;
            }
            if (Stationarity.isStationary(x) && Stationarity.isStationary(y)) {
                bothStationary++;
            }
        }
        assertTrue(sumAbsR / TRIALS < 0.08,
                "mean |correlation| of independent white noise should be tiny, was " + (sumAbsR / TRIALS));
        assertTrue(lowRaw >= TRIALS - 2,
                "nearly all white-noise pairs should have low correlation, " + lowRaw + "/" + TRIALS);
        // Data-driven d=0 fixes the over-differencing that used to flag ~40% of these as related.
        assertTrue(whitenedIndependent >= TRIALS - TRIALS / 5,
                "whitening should call independent white noise independent, " + whitenedIndependent + "/" + TRIALS);
        // Both stationary ⇒ the compute suppresses cointegration, so no spurious lasting link.
        assertTrue(bothStationary >= TRIALS - 2,
                "white noise should test stationary so cointegration is gated off, " + bothStationary + "/" + TRIALS);
    }

    /**
     * Coherence should say "related on THIS timescale" — high for a genuinely shared rhythm, and
     * clearly lower for unrelated series (averaged over draws to beat the max-over-bins bias).
     */
    @Test
    void coherence_isHighForSharedRhythm_lowerForUnrelatedSeries() {
        Random rnd = new Random(9L);
        int n = 4096;
        double periodBuckets = 32;
        double[] sx = new double[n];
        double[] sy = new double[n];
        for (int i = 0; i < n; i++) {
            double shared = Math.sin(2 * Math.PI * i / periodBuckets);
            sx[i] = shared + 0.3 * rnd.nextGaussian();
            sy[i] = shared + 0.3 * rnd.nextGaussian();
        }
        double sharedPeak = Coherence.welch(sx, sy, BUCKET, null).peakCoherence();

        int reps = 5;
        double unrelatedPeakSum = 0;
        for (int t = 0; t < reps; t++) {
            double[] ux = gaussianSeries(n, rnd);
            double[] uy = gaussianSeries(n, rnd);
            unrelatedPeakSum += Coherence.welch(ux, uy, BUCKET, null).peakCoherence();
        }
        double unrelatedPeak = unrelatedPeakSum / reps;

        assertTrue(sharedPeak > 0.7, "shared rhythm should have high peak coherence, was " + sharedPeak);
        assertTrue(unrelatedPeak < sharedPeak - 0.2,
                "unrelated series should have clearly lower peak coherence: shared=" + sharedPeak
                        + " unrelated=" + unrelatedPeak);
    }

    /**
     * Stability is the spuriousness flag: a relationship that flips sign partway through the window is
     * flagged unstable, while a consistent one is stable.
     */
    @Test
    void episodicRelationship_isFlaggedUnstable_whileConsistentOneIsStable() {
        Random rnd = new Random(77L);
        double[] x = gaussianSeries(N, rnd);

        // Episodic: y tracks x over the first 5/8 of the window, then anti-tracks — signs disagree
        // across sub-windows.
        double[] yEpisodic = new double[N];
        int flip = 5 * N / 8;
        for (int i = 0; i < N; i++) {
            yEpisodic[i] = (i < flip ? x[i] : -x[i]) + 0.05 * rnd.nextGaussian();
        }
        CrossCorrelation.Stability episodic = CrossCorrelation.stability(x, yEpisodic, WINDOWS);
        assertNotNull(episodic);
        assertTrue(!episodic.stable(),
                "a sign-flipping relationship should be flagged unstable, signConsistency=" + episodic.signConsistency());

        // Consistent: y tracks x throughout.
        double[] yConsistent = new double[N];
        for (int i = 0; i < N; i++) {
            yConsistent[i] = x[i] + 0.05 * rnd.nextGaussian();
        }
        CrossCorrelation.Stability consistent = CrossCorrelation.stability(x, yConsistent, WINDOWS);
        assertNotNull(consistent);
        assertTrue(consistent.stable(),
                "a consistent relationship should be stable, signConsistency=" + consistent.signConsistency());
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Pre-whiten both series with the SAME data-driven differencing order the compute now uses
     * (0 when both are stationary, else 1 — see {@link Stationarity}), then return the Haugh–Box
     * independence p-value.
     */
    private static double whitenedPValue(double[] x, double[] y) {
        int d = Math.min(1, Math.max(Stationarity.integrationOrder(x), Stationarity.integrationOrder(y)));
        ArimaModel mx = ArimaModel.fit(x, 1, d, 0);
        ArimaModel my = ArimaModel.fit(y, 1, d, 0);
        return HaughBox.analyze(mx.residuals(), my.residuals(), MAX_LAG).pValue();
    }

    private static double[] randomWalk(int n, Random rnd) {
        double[] s = new double[n];
        for (int i = 1; i < n; i++) {
            s[i] = s[i - 1] + rnd.nextGaussian();
        }
        return s;
    }

    private static double[] gaussianSeries(int n, Random rnd) {
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            s[i] = rnd.nextGaussian();
        }
        return s;
    }
}
