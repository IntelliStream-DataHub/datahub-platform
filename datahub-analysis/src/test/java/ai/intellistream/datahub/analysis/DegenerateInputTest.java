// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Degenerate- and edge-input coverage for the statistics engine. The contract each test pins down is a
 * hard one: on pathological inputs — constant/zero-variance series, too-short series, large magnitudes,
 * identical series — every statistic must either return a FINITE value, return {@code null}, or throw,
 * but NEVER emit a silent NaN/Infinity (which would corrupt the ranking and break the JSON response).
 * Companion to the end-to-end {@code AnalysisComputerTest.constantSeriesProduceNoNonFiniteValues}.
 */
class DegenerateInputTest {

    private static final int N = 200;
    private static final int MAX_LAG = 20;
    private static final int BUCKET = 60;

    // --- constant / zero-variance series (the classic degenerate case) --------------------------

    @Test
    void crossCorrelationOnAConstantSeriesIsZeroAndFinite() {
        double[] flat = constant(5.0);
        double[] wave = sine();
        assertEquals(0.0, CrossCorrelation.pearson(flat, wave), 1e-12, "constant vs varying ⇒ 0");
        assertEquals(0.0, CrossCorrelation.pearson(flat, flat), 1e-12, "constant vs constant ⇒ 0");
        CrossCorrelation.CcfResult ccf = CrossCorrelation.ccf(flat, wave, MAX_LAG);
        assertEquals(0.0, ccf.bestR(), 1e-12);
        assertAllFinite(ccf.ccf());
    }

    @Test
    void stabilityOnAConstantSeriesIsFinite() {
        double[] flat = constant(3.0);
        CrossCorrelation.Stability st = CrossCorrelation.stability(flat, flat, 8);
        assertNotNull(st);
        assertTrue(Double.isFinite(st.signConsistency()), "signConsistency finite");
        assertTrue(Double.isFinite(st.corrStdDev()), "corrStdDev finite");
    }

    @Test
    void coherenceOnConstantSeriesIsFinite() {
        Coherence.Result res = Coherence.welch(constant(1.0), constant(-2.0), BUCKET, null);
        if (res != null) { // welch is null when segments are too few; either outcome is acceptable
            assertTrue(Double.isFinite(res.peakCoherence()), "peakCoherence finite");
            assertAllFinite(res.coherence());
        }
    }

    @Test
    void stationarityCallsAConstantSeriesStationaryWithoutThrowing() {
        double[] flat = constant(7.0);
        assertTrue(Stationarity.isStationary(flat), "a constant series is degenerately stationary");
        assertEquals(0, Stationarity.integrationOrder(flat));
        assertTrue(Double.isFinite(Stationarity.adfTStat(flat, 1, true)), "ADF with constant is finite");
        assertTrue(Double.isFinite(Stationarity.adfTStat(flat, 1, false)), "ADF without constant is finite");
    }

    @Test
    void arimaNeverYieldsNaNResidualsOnAConstantSeries() {
        double[] flat = constant(4.0);
        try {
            double[] residuals = ArimaModel.fit(flat, 1, 1, 0).residuals();
            assertAllFinite(residuals); // if it fits, the residuals must be finite...
        } catch (RuntimeException degenerate) {
            // ...otherwise it must fail loudly (the compute's catch handles it) — never a silent NaN.
        }
    }

    @Test
    void haughBoxOnConstantResidualsIsFinite() {
        double[] flat = constant(0.5);
        HaughBox.Result hb = HaughBox.analyze(flat, flat, MAX_LAG);
        assertTrue(Double.isFinite(hb.bestR()));
        assertTrue(Double.isFinite(hb.statistic()));
        assertTrue(Double.isFinite(hb.pValue()));
    }

    @Test
    void cointegrationThrowsOnAConstantRegressorButHandlesAConstantResponse() {
        // A constant x (the regressor) has zero variance ⇒ SimpleRegression returns a NaN slope (it does
        // not throw). Cointegration.test must fail loudly so the compute skips it, not emit a NaN stat.
        assertThrows(IllegalArgumentException.class,
                () -> Cointegration.test(constant(2.0), randomWalk(1L)));
        // A constant y (the response) is fine: the slope is a finite 0 and the residual is flat ⇒ not
        // cointegrated, with a finite statistic.
        Cointegration.Result res = Cointegration.test(randomWalk(2L), constant(2.0));
        assertTrue(Double.isFinite(res.adfStat()), "adfStat finite for a constant response");
        assertFalse(res.cointegrated());
    }

    // --- other edges ----------------------------------------------------------------------------

    @Test
    void largeMagnitudesStayFinite() {
        double[] big = new double[N];
        double[] wave = new double[N];
        for (int i = 0; i < N; i++) {
            big[i] = (i % 2 == 0 ? 1 : -1) * 1e6 + Math.sin(i * 0.2); // large but well within double range
            wave[i] = Math.sin(i * 0.2);
        }
        assertTrue(Double.isFinite(CrossCorrelation.pearson(big, wave)));
        assertAllFinite(CrossCorrelation.ccf(big, wave, MAX_LAG).ccf());
        Coherence.Result coh = Coherence.welch(big, wave, BUCKET, null);
        if (coh != null) {
            assertTrue(Double.isFinite(coh.peakCoherence()));
        }
    }

    @Test
    void tooShortInputsAreRejectedOrNull_neverNaN() {
        double[] tiny = {1.0, 2.0, 3.0};
        assertThrows(IllegalArgumentException.class, () -> Cointegration.test(tiny, tiny), "below minLength");
        assertNull(CrossCorrelation.stability(tiny, tiny, 8), "too few points for >= 4 windows");
        assertFalse(Stationarity.isStationary(tiny), "too short ⇒ conservatively non-stationary");
    }

    @Test
    void identicalSeriesGiveFiniteMaximalCorrelation() {
        double[] wave = sine();
        assertEquals(1.0, CrossCorrelation.pearson(wave, wave), 1e-9, "self-correlation is 1");
        CrossCorrelation.CcfResult ccf = CrossCorrelation.ccf(wave, wave, MAX_LAG);
        assertTrue(Double.isFinite(ccf.bestR()));
        assertEquals(0, ccf.bestLag(), "identical series align at lag 0");
    }

    // --- helpers --------------------------------------------------------------------------------

    private static void assertAllFinite(double[] a) {
        for (double v : a) {
            assertTrue(Double.isFinite(v), "expected finite, got " + v);
        }
    }

    private static double[] constant(double c) {
        double[] a = new double[N];
        Arrays.fill(a, c);
        return a;
    }

    private static double[] sine() {
        double[] a = new double[N];
        for (int i = 0; i < N; i++) {
            a[i] = Math.sin(i * 0.2);
        }
        return a;
    }

    private static double[] randomWalk(long seed) {
        Random rnd = new Random(seed);
        double[] a = new double[N];
        for (int i = 1; i < N; i++) {
            a[i] = a[i - 1] + rnd.nextGaussian();
        }
        return a;
    }
}
