// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.apache.commons.math3.stat.regression.SimpleRegression;

/**
 * Engle–Granger two-step cointegration test. Detects a genuine shared stochastic trend — the
 * legitimate "they co-trend because they're physically coupled" case that ARIMA pre-whitening
 * discards.
 *
 * <ol>
 *   <li>OLS-regress {@code y} on {@code x}; take the residuals {@code r = y - (a + b·x)}.</li>
 *   <li>Augmented Dickey–Fuller test for a unit root in {@code r} (no constant, since the residuals
 *       are mean-zero by construction). If {@code r} is stationary, {@code x} and {@code y} are
 *       cointegrated.</li>
 * </ol>
 *
 * The ADF t-statistic is compared to the Engle–Granger critical value for two variables (no trend),
 * not the standard-normal quantile — the residual-based null has a non-standard distribution.
 */
public final class Cointegration {

    private Cointegration() {
    }

    /** Engle–Granger 5% critical value, two variables, no trend (MacKinnon, large sample). */
    public static final double CRIT_5PCT = -3.34;

    /** Number of augmentation lags in the ADF regression. */
    public static final int ADF_LAGS = 1;

    /**
     * @param adfStat      ADF t-statistic on the cointegrating residual (more negative ⇒ stationary)
     * @param crit5        the 5% critical value used
     * @param cointegrated whether {@code adfStat < crit5}
     */
    public record Result(double adfStat, double crit5, boolean cointegrated) {
    }

    /** Minimum length needed to run the test with {@link #ADF_LAGS} augmentation lags. */
    public static int minLength() {
        return ADF_LAGS + 8;
    }

    public static Result test(double[] x, double[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("series must be the same length");
        }
        if (x.length < minLength()) {
            throw new IllegalArgumentException("series too short for cointegration test");
        }
        // Step 1: cointegrating regression y = a + b·x, residuals r.
        SimpleRegression reg = new SimpleRegression(true);
        for (int i = 0; i < x.length; i++) {
            reg.addData(x[i], y[i]);
        }
        double a = reg.getIntercept();
        double b = reg.getSlope();
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            // A constant x has zero variance, so SimpleRegression returns NaN (it does not throw). There
            // is no meaningful cointegrating regression; fail so the caller's catch leaves the result
            // unset rather than propagating a NaN statistic into the response.
            throw new IllegalArgumentException("cointegration regressor is degenerate (constant series)");
        }
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            r[i] = y[i] - (a + b * x[i]);
        }
        // Step 2: ADF on the mean-zero residual r (no constant): Δr[j] = ρ·r[j] + Σ γ_i·Δr[j-i] + ε.
        double adfStat = Stationarity.adfTStat(r, ADF_LAGS, false);
        return new Result(adfStat, CRIT_5PCT, adfStat < CRIT_5PCT);
    }
}
