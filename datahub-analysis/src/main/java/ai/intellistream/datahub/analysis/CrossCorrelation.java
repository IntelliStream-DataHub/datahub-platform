// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

/**
 * Normalized cross-correlation of two equal-length, contiguous series over a range of lags, plus a
 * cheap subwindow-stability check used as a spuriousness flag.
 *
 * <p>Lag convention: a positive {@code bestLag k} means {@code y} is compared at {@code t+k} against
 * {@code x} at {@code t} — i.e. {@code x} leads {@code y} by {@code k} buckets.
 */
public final class CrossCorrelation {

    private CrossCorrelation() {
    }

    /** Result of a cross-correlation scan. {@code ccf[i]} is the coefficient at lag {@code i - maxLag}. */
    public record CcfResult(double bestR, int bestLag, double[] ccf, int maxLag) {
    }

    public static CcfResult ccf(double[] x, double[] y, int maxLag) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("series must be the same length");
        }
        int n = x.length;
        if (maxLag >= n) {
            maxLag = n - 1;
        }
        double mx = mean(x);
        double my = mean(y);
        double sx = Math.sqrt(sumSq(x, mx));
        double sy = Math.sqrt(sumSq(y, my));
        double denom = sx * sy;

        double[] ccf = new double[2 * maxLag + 1];
        double bestR = 0;
        int bestLag = 0;
        boolean haveBest = false;
        for (int k = -maxLag; k <= maxLag; k++) {
            double num = 0;
            // t ranges over indices where both x[t] and y[t+k] exist
            int tStart = Math.max(0, -k);
            int tEnd = Math.min(n, n - k);
            for (int t = tStart; t < tEnd; t++) {
                num += (x[t] - mx) * (y[t + k] - my);
            }
            double r = denom == 0 ? 0 : num / denom;
            ccf[k + maxLag] = r;
            if (!haveBest || Math.abs(r) > Math.abs(bestR)) {
                bestR = r;
                bestLag = k;
                haveBest = true;
            }
        }
        return new CcfResult(bestR, bestLag, ccf, maxLag);
    }

    /** Plain Pearson correlation of two equal-length arrays. */
    public static double pearson(double[] x, double[] y) {
        int n = x.length;
        double mx = mean(x);
        double my = mean(y);
        double num = 0;
        double dx = 0;
        double dy = 0;
        for (int i = 0; i < n; i++) {
            double a = x[i] - mx;
            double b = y[i] - my;
            num += a * b;
            dx += a * a;
            dy += b * b;
        }
        double denom = Math.sqrt(dx * dy);
        return denom == 0 ? 0 : num / denom;
    }

    /** Subwindow correlation-stability result; {@code stable} when the relationship holds across windows. */
    public record Stability(double signConsistency, double corrStdDev, boolean stable, int windows) {
    }

    /** Minimum points per subwindow for a meaningful per-window correlation. */
    public static final int MIN_WINDOW_POINTS = 10;

    /** Sign-consistency threshold above which the relationship is considered stable. */
    public static final double STABLE_THRESHOLD = 0.8;

    /**
     * Split the series into {@code windows} contiguous chunks, correlate each, and measure how
     * consistently the per-window correlation keeps the sign of the full-window correlation.
     * Returns {@code null} when there aren't enough points for {@code >= 4} usable windows.
     */
    public static Stability stability(double[] x, double[] y, int windows) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("series must be the same length");
        }
        int n = x.length;
        int usable = Math.min(windows, n / MIN_WINDOW_POINTS);
        if (usable < 4) {
            return null;
        }
        double full = pearson(x, y);
        double fullSign = Math.signum(full);
        int chunk = n / usable;
        double agree = 0;
        double sum = 0;
        double sumSq = 0;
        for (int w = 0; w < usable; w++) {
            int from = w * chunk;
            int to = (w == usable - 1) ? n : from + chunk;
            double[] xw = java.util.Arrays.copyOfRange(x, from, to);
            double[] yw = java.util.Arrays.copyOfRange(y, from, to);
            double r = pearson(xw, yw);
            sum += r;
            sumSq += r * r;
            if (fullSign == 0 || Math.signum(r) == fullSign) {
                agree++;
            }
        }
        double signConsistency = agree / usable;
        double meanR = sum / usable;
        double var = Math.max(0, sumSq / usable - meanR * meanR);
        double std = Math.sqrt(var);
        return new Stability(signConsistency, std, signConsistency >= STABLE_THRESHOLD, usable);
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s / a.length;
    }

    private static double sumSq(double[] a, double mean) {
        double s = 0;
        for (double v : a) {
            double d = v - mean;
            s += d * d;
        }
        return s;
    }
}
