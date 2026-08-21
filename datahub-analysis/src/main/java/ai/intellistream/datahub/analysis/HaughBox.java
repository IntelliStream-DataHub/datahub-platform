// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.apache.commons.math3.distribution.ChiSquaredDistribution;

/**
 * Haugh–Box test for independence between two timeseries. Each series is pre-whitened by its own
 * ARIMA model (see {@link ArimaModel}); this class cross-correlates the two residual (innovation)
 * series and forms the portmanteau statistic
 *
 * <pre>S = n · Σ_{k=-M}^{M} r²_xy(k)  ~  χ²(2M+1)</pre>
 *
 * under the null of independence. A small p-value means the series are related; the peak of the
 * residual cross-correlation gives the lead/lag.
 */
public final class HaughBox {

    private HaughBox() {
    }

    /**
     * @param bestR        peak residual cross-correlation
     * @param bestLag      lag (buckets) of the peak; positive ⇒ x leads y
     * @param statistic    Haugh–Box S statistic
     * @param pValue       χ²(2·maxLag+1) upper-tail probability of S (independence p-value)
     * @param significant  whether the peak exceeds the ±2/√n white-noise band
     */
    public record Result(double bestR, int bestLag, double statistic, double pValue, boolean significant) {
    }

    public static Result analyze(double[] residualsX, double[] residualsY, int maxLag) {
        if (residualsX.length != residualsY.length) {
            throw new IllegalArgumentException("residual series must be the same length");
        }
        int n = residualsX.length;
        CrossCorrelation.CcfResult ccf = CrossCorrelation.ccf(residualsX, residualsY, maxLag);
        double sumSq = 0;
        for (double r : ccf.ccf()) {
            sumSq += r * r;
        }
        double statistic = n * sumSq;
        int dof = ccf.ccf().length; // 2*effectiveMaxLag + 1
        double pValue = 1.0 - new ChiSquaredDistribution(dof).cumulativeProbability(statistic);
        boolean significant = Math.abs(ccf.bestR()) > 2.0 / Math.sqrt(n);
        return new Result(ccf.bestR(), ccf.bestLag(), statistic, pValue, significant);
    }
}
