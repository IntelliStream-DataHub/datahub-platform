// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADF unit-root pretest that gates whitening's differencing order and cointegration. Seeded,
 * realization-based so the pass/fail doesn't hinge on one lucky draw.
 */
class StationarityTest {

    private static final int N = 600;
    private static final int TRIALS = 30;

    @Test
    void meanRevertingSeriesTestStationary() {
        Random rnd = new Random(1L);
        int stationaryNoise = 0;
        int stationaryAr = 0;
        for (int t = 0; t < TRIALS; t++) {
            double[] noise = new double[N];
            double[] ar = new double[N];
            for (int i = 0; i < N; i++) {
                noise[i] = rnd.nextGaussian();
            }
            for (int i = 1; i < N; i++) {
                ar[i] = 0.5 * ar[i - 1] + rnd.nextGaussian(); // AR(1), mean-reverting
            }
            if (Stationarity.isStationary(noise)) {
                stationaryNoise++;
            }
            if (Stationarity.isStationary(ar)) {
                stationaryAr++;
            }
        }
        assertTrue(stationaryNoise >= TRIALS - 2, "white noise should test stationary, " + stationaryNoise + "/" + TRIALS);
        assertTrue(stationaryAr >= TRIALS - 3, "AR(1) should test stationary, " + stationaryAr + "/" + TRIALS);
    }

    @Test
    void randomWalksAndTrendsTestNonStationary() {
        Random rnd = new Random(2L);
        int nonStationaryWalk = 0;
        for (int t = 0; t < TRIALS; t++) {
            double[] walk = new double[N];
            for (int i = 1; i < N; i++) {
                walk[i] = walk[i - 1] + rnd.nextGaussian();
            }
            if (!Stationarity.isStationary(walk)) {
                nonStationaryWalk++;
            }
        }
        assertTrue(nonStationaryWalk >= TRIALS - 4,
                "random walks should test non-stationary, " + nonStationaryWalk + "/" + TRIALS);

        // A deterministic trend must read as non-stationary, so whitening differences it away.
        double[] trend = new double[N];
        for (int i = 0; i < N; i++) {
            trend[i] = 0.05 * i + rnd.nextGaussian();
        }
        assertFalse(Stationarity.isStationary(trend), "a trending series should test non-stationary");
    }

    @Test
    void integrationOrder_isZeroForStationary_oneForUnitRoot() {
        Random rnd = new Random(4L);
        int walkOrderOne = 0;
        int noiseOrderZero = 0;
        for (int t = 0; t < TRIALS; t++) {
            double[] noise = new double[N];
            double[] walk = new double[N];
            for (int i = 0; i < N; i++) {
                noise[i] = rnd.nextGaussian();
            }
            for (int i = 1; i < N; i++) {
                walk[i] = walk[i - 1] + rnd.nextGaussian();
            }
            if (Stationarity.integrationOrder(noise) == 0) {
                noiseOrderZero++;
            }
            if (Stationarity.integrationOrder(walk) == 1) {
                walkOrderOne++;
            }
        }
        assertTrue(noiseOrderZero >= TRIALS - 2, "stationary noise should be order 0, " + noiseOrderZero + "/" + TRIALS);
        assertTrue(walkOrderOne >= TRIALS - 4, "random walks should be order 1, " + walkOrderOne + "/" + TRIALS);

        // Too short to test ⇒ conservative non-stationary (order 1).
        assertEquals(1, Stationarity.integrationOrder(new double[]{1, 2, 3}));
    }

    @Test
    void constantSeriesIsStationary() {
        // A constant series is degenerately stationary (zero variance, all moments time-invariant) — the
        // opposite of a unit root, even though the raw ADF regression is singular on it.
        double[] flat = new double[N];
        java.util.Arrays.fill(flat, 7.0);
        assertTrue(Stationarity.isStationary(flat), "a constant series should test stationary");
        assertEquals(0, Stationarity.integrationOrder(flat), "constant ⇒ no differencing needed");
    }
}
