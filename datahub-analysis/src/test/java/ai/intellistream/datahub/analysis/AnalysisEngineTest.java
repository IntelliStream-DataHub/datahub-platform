// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic (seeded) checks of the analysis engine on synthetic signals with known structure.
 */
class AnalysisEngineTest {

    @Test
    void crossCorrelationFindsKnownLag() {
        Random rnd = new Random(42);
        int n = 600;
        int lag = 7;
        double[] base = new double[n];
        for (int i = 0; i < n; i++) {
            base[i] = Math.sin(i * 0.1) + 0.05 * rnd.nextGaussian();
        }
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = base[i];
            // y lags x by `lag`: y[t] = x[t-lag]
            y[i] = i - lag >= 0 ? base[i - lag] : 0;
        }
        CrossCorrelation.CcfResult r = CrossCorrelation.ccf(x, y, 30);
        // x leads y by `lag` ⇒ positive bestLag
        assertEquals(lag, r.bestLag());
        assertTrue(r.bestR() > 0.8, "expected strong positive correlation, got " + r.bestR());
    }

    @Test
    void crossCorrelationDetectsAntiCorrelation() {
        int n = 400;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = Math.sin(i * 0.2);
            y[i] = -Math.sin(i * 0.2);
        }
        CrossCorrelation.CcfResult r = CrossCorrelation.ccf(x, y, 10);
        assertEquals(0, r.bestLag());
        assertTrue(r.bestR() < -0.95, "expected strong anti-correlation, got " + r.bestR());
    }

    @Test
    void arimaRecoversAr1AndWhitens() {
        Random rnd = new Random(7);
        int n = 2000;
        double phi = 0.6;
        double[] s = new double[n];
        for (int i = 1; i < n; i++) {
            s[i] = phi * s[i - 1] + rnd.nextGaussian();
        }
        ArimaModel m = ArimaModel.fit(s, 1, 0, 0);
        assertEquals(phi, m.arCoeffs()[0], 0.08);
        // Residuals should be close to white: lag-1 autocorrelation near zero.
        double[] e = m.residuals();
        double[] e0 = java.util.Arrays.copyOfRange(e, 0, e.length - 1);
        double[] e1 = java.util.Arrays.copyOfRange(e, 1, e.length);
        double ac1 = CrossCorrelation.pearson(e0, e1);
        assertTrue(Math.abs(ac1) < 0.1, "residual autocorrelation should be small, got " + ac1);
    }

    @Test
    void haughBoxFlagsDependenceAndIndependence() {
        Random rnd = new Random(11);
        int n = 1500;
        // Dependent: y driven by x with a lag, both AR(1).
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 1; i < n; i++) {
            x[i] = 0.5 * x[i - 1] + rnd.nextGaussian();
        }
        int lag = 4;
        for (int i = 0; i < n; i++) {
            y[i] = (i - lag >= 0 ? 0.8 * x[i - lag] : 0) + 0.3 * rnd.nextGaussian();
        }
        ArimaModel mx = ArimaModel.fit(x, 1, 0, 0);
        ArimaModel my = ArimaModel.fit(y, 1, 0, 0);
        HaughBox.Result dep = HaughBox.analyze(mx.residuals(), my.residuals(), 20);
        assertTrue(dep.pValue() < 0.05, "dependent series should reject independence, p=" + dep.pValue());

        // Independent white-noise series.
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextGaussian();
            b[i] = rnd.nextGaussian();
        }
        ArimaModel ma = ArimaModel.fit(a, 1, 0, 0);
        ArimaModel mb = ArimaModel.fit(b, 1, 0, 0);
        HaughBox.Result indep = HaughBox.analyze(ma.residuals(), mb.residuals(), 20);
        assertTrue(indep.pValue() > 0.05, "independent series should not reject, p=" + indep.pValue());
    }

    @Test
    void cointegrationDistinguishesSharedTrend() {
        Random rnd = new Random(3);
        int n = 800;
        double[] x = new double[n];
        for (int i = 1; i < n; i++) {
            x[i] = x[i - 1] + rnd.nextGaussian(); // random walk
        }
        double[] yCo = new double[n];
        double[] yIndep = new double[n];
        double[] w = new double[n];
        for (int i = 1; i < n; i++) {
            w[i] = w[i - 1] + rnd.nextGaussian(); // independent random walk
        }
        for (int i = 0; i < n; i++) {
            yCo[i] = 2.0 * x[i] + rnd.nextGaussian(); // cointegrated with x
            yIndep[i] = w[i];                         // not cointegrated with x
        }
        assertTrue(Cointegration.test(x, yCo).cointegrated(), "x and 2x+noise should be cointegrated");
        assertTrue(!Cointegration.test(x, yIndep).cointegrated(), "independent walks should not be cointegrated");
    }

    @Test
    void stabilityFlagsSpuriousRandomWalks() {
        Random rnd = new Random(5);
        int n = 800;
        // Genuinely correlated, stationary.
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double common = rnd.nextGaussian();
            x[i] = common + 0.2 * rnd.nextGaussian();
            y[i] = common + 0.2 * rnd.nextGaussian();
        }
        CrossCorrelation.Stability stable = CrossCorrelation.stability(x, y, 8);
        assertNotNull(stable);
        assertTrue(stable.stable(), "common-driver pair should be stable, sc=" + stable.signConsistency());
    }

    @Test
    void coherencePeaksAtSharedFrequency() {
        Random rnd = new Random(9);
        int n = 4096;
        int bucketSeconds = 60;
        double periodBuckets = 32; // shared oscillation period in buckets
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double shared = Math.sin(2 * Math.PI * i / periodBuckets);
            x[i] = shared + 0.3 * rnd.nextGaussian();
            y[i] = shared + 0.3 * rnd.nextGaussian();
        }
        Coherence.Result r = Coherence.welch(x, y, bucketSeconds, null);
        assertNotNull(r);
        double expectedPeriodSeconds = periodBuckets * bucketSeconds;
        assertEquals(expectedPeriodSeconds, r.peakPeriodSeconds(), expectedPeriodSeconds * 0.25);
        assertTrue(r.peakCoherence() > 0.7, "expected high coherence at shared freq, got " + r.peakCoherence());
    }
}
