// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

/**
 * Augmented Dickey–Fuller (ADF) unit-root test, used to decide whether a series is stationary (I(0),
 * mean-reverting) or has a unit root (I(1), random-walk-like). It underpins two "don't fool the user"
 * guards in the analysis:
 *
 * <ul>
 *   <li><b>Whitening</b> picks the ARIMA differencing order {@code d} from the data instead of always
 *       differencing: an already-stationary series is left alone ({@code d=0}), so the Haugh–Box test
 *       isn't handed over-differenced, non-white residuals.</li>
 *   <li><b>Cointegration</b> only runs when both series are non-stationary — the only case where it is
 *       meaningful — so a pair of stationary-but-unrelated series can't produce a spurious "lasting
 *       link".</li>
 * </ul>
 *
 * <p>The regression is {@code Δr[t] = ρ·r[t-1] + (c) + Σ γ_i·Δr[t-i] + ε}; the returned statistic is
 * the t-ratio of {@code ρ̂}. Under the unit-root null it follows the Dickey–Fuller distribution, so it
 * is compared to a DF critical value, not the normal quantile.
 */
public final class Stationarity {

    private Stationarity() {
    }

    /** Augmentation lags in the ADF regression (matches {@link Cointegration}). */
    public static final int ADF_LAGS = 1;

    /** Dickey–Fuller 5% critical value with a constant, no trend (MacKinnon, large sample). */
    public static final double CRIT_5PCT_CONSTANT = -2.86;

    /** Minimum length for a usable ADF regression with a constant and {@link #ADF_LAGS} lags. */
    public static int minLength() {
        return ADF_LAGS + 10;
    }

    /**
     * @return {@code true} if {@code series} rejects the unit-root null at 5% (stationary / mean-reverting).
     *         Too-short series return {@code false} (treated as non-stationary — the conservative choice:
     *         it keeps differencing on for whitening and doesn't suppress the cointegration test).
     */
    public static boolean isStationary(double[] series) {
        if (series == null || series.length < minLength()) {
            return false;
        }
        // A constant series is degenerately stationary: zero variance, all moments time-invariant — the
        // opposite of a unit root. The ADF regression is singular on it (Δr ≡ 0 and the level column
        // collapses into the intercept), so short-circuit. This is the correct label AND what keeps a
        // flatline series out of the cointegration path, whose regression would divide by its zero
        // variance and emit a NaN statistic.
        if (isConstant(series)) {
            return true;
        }
        return adfTStat(series, ADF_LAGS, true) < CRIT_5PCT_CONSTANT;
    }

    private static boolean isConstant(double[] series) {
        for (int i = 1; i < series.length; i++) {
            if (series[i] != series[0]) {
                return false;
            }
        }
        return true;
    }

    /** Integration order for whitening: 0 when stationary, 1 (needs differencing) when it has a unit root. */
    public static int integrationOrder(double[] series) {
        return isStationary(series) ? 0 : 1;
    }

    /**
     * ADF t-statistic of the lagged-level coefficient (more negative ⇒ more stationary).
     *
     * @param includeConstant add an intercept column — use for a raw series that may have a non-zero
     *                        mean; omit for a mean-zero residual (as in {@link Cointegration}).
     */
    public static double adfTStat(double[] r, int lags, boolean includeConstant) {
        int L = r.length;
        if (L < 3) {
            return 0;
        }
        double[] dr = new double[L - 1];
        for (int i = 0; i < dr.length; i++) {
            dr[i] = r[i + 1] - r[i];
        }
        int extra = includeConstant ? 1 : 0;
        int predictors = 1 + lags + extra;
        int rows = dr.length - lags;
        if (rows <= predictors + 1) {
            // Not enough data to augment; fall back to a plain (non-augmented) Dickey–Fuller.
            lags = 0;
            predictors = 1 + extra;
            rows = dr.length;
        }
        double[] resp = new double[rows];
        double[][] design = new double[rows][predictors];
        for (int j = lags; j < dr.length; j++) {
            int row = j - lags;
            resp[row] = dr[j];
            design[row][0] = r[j]; // level term r[t-1]
            for (int i = 1; i <= lags; i++) {
                design[row][i] = dr[j - i];
            }
            if (includeConstant) {
                design[row][predictors - 1] = 1.0;
            }
        }
        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.setNoIntercept(true); // the constant, when present, is an explicit column
        ols.newSampleData(resp, design);
        try {
            double[] beta = ols.estimateRegressionParameters();
            double[] se = ols.estimateRegressionParametersStandardErrors();
            if (se[0] == 0 || !Double.isFinite(beta[0]) || !Double.isFinite(se[0])) {
                return 0; // degenerate regressor ⇒ no evidence either way; treat as inconclusive
            }
            return beta[0] / se[0];
        } catch (RuntimeException e) {
            return 0; // singular design (near-constant series) ⇒ inconclusive rather than throwing
        }
    }
}
