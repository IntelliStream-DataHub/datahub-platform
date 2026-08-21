// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

/**
 * A simple ARIMA(p, d, q) model used as a pre-whitening filter for the Haugh–Box procedure.
 *
 * <p>Each series is fitted with its <em>own</em> model: difference {@code d} times, then fit an
 * AR(p) on the differenced series by ordinary least squares (with intercept). {@code q} (the MA
 * order) is accepted but only {@code q == 0} is implemented — a pure AR whitening, which is
 * well-conditioned and deterministic. The fitted model exposes the residuals (innovations) of the
 * series it was fitted on; cross-correlating two series' residuals is the Haugh–Box test.
 *
 * <p>Two series fitted with the <em>same</em> {@code (p, d)} produce residual arrays of equal length
 * that are index-aligned (both start at original index {@code d + p}), so their residuals can be
 * cross-correlated directly.
 */
public final class ArimaModel {

    private final int p;
    private final int d;
    private final double intercept;
    private final double[] arCoeffs; // length p
    private final double[] residuals;

    private ArimaModel(int p, int d, double intercept, double[] arCoeffs, double[] residuals) {
        this.p = p;
        this.d = d;
        this.intercept = intercept;
        this.arCoeffs = arCoeffs;
        this.residuals = residuals;
    }

    public int p() {
        return p;
    }

    public int d() {
        return d;
    }

    public double intercept() {
        return intercept;
    }

    public double[] arCoeffs() {
        return arCoeffs.clone();
    }

    /** Innovations of the series this model was fitted on (length {@code n - d - p}). */
    public double[] residuals() {
        return residuals;
    }

    /** Minimum series length required to fit ARIMA(p, d, q): need enough rows after differencing. */
    public static int minLength(int p, int d) {
        // After d differences we have n-d points; AR(p) regression needs > p+1 rows.
        return d + p + p + 2;
    }

    public static ArimaModel fit(double[] series, int p, int d, int q) {
        if (q != 0) {
            throw new IllegalArgumentException("Only q=0 (pure AR whitening) is implemented");
        }
        if (p < 0 || d < 0) {
            throw new IllegalArgumentException("p and d must be non-negative");
        }
        if (series.length < minLength(p, d)) {
            throw new IllegalArgumentException("series too short for ARIMA(" + p + "," + d + "," + q + ")");
        }
        double[] diff = difference(series, d);
        int m = diff.length;
        int rows = m - p;
        if (rows <= p + 1) {
            throw new IllegalArgumentException("series too short after differencing");
        }

        double intercept;
        double[] ar;
        double[] residuals = new double[rows];

        if (p == 0) {
            // No AR terms: the model is just the mean of the differenced series.
            double mean = 0;
            for (double v : diff) {
                mean += v;
            }
            mean /= m;
            intercept = mean;
            ar = new double[0];
            for (int t = 0; t < rows; t++) {
                residuals[t] = diff[t] - mean;
            }
        } else {
            double[] y = new double[rows];
            double[][] x = new double[rows][p];
            for (int t = p; t < m; t++) {
                y[t - p] = diff[t];
                for (int lag = 1; lag <= p; lag++) {
                    x[t - p][lag - 1] = diff[t - lag];
                }
            }
            OLSMultipleLinearRegression reg = new OLSMultipleLinearRegression();
            reg.newSampleData(y, x);
            double[] beta = reg.estimateRegressionParameters(); // [intercept, ar1, ar2, ...]
            intercept = beta[0];
            ar = new double[p];
            System.arraycopy(beta, 1, ar, 0, p);
            for (int t = p; t < m; t++) {
                double pred = intercept;
                for (int lag = 1; lag <= p; lag++) {
                    pred += ar[lag - 1] * diff[t - lag];
                }
                residuals[t - p] = diff[t] - pred;
            }
        }
        return new ArimaModel(p, d, intercept, ar, residuals);
    }

    /** Apply {@code d} successive first-differences to {@code s}. */
    public static double[] difference(double[] s, int d) {
        double[] cur = s.clone();
        for (int k = 0; k < d; k++) {
            double[] next = new double[cur.length - 1];
            for (int i = 0; i < next.length; i++) {
                next[i] = cur[i + 1] - cur[i];
            }
            cur = next;
        }
        return cur;
    }
}
