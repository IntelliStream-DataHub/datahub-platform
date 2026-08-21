// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

/**
 * Resamples irregular timeseries onto a common regular grid so the downstream analyses (ARIMA,
 * cross-correlation, cointegration, coherence) can assume evenly-spaced, index-aligned arrays.
 *
 * <p>The datapoints are already bucketed by ClickHouse at a fixed bucket width; this class places
 * each bucket's value at its grid index, fills short interior gaps by linear interpolation, and
 * aligns a pair of series to their common non-missing overlap.
 */
public final class TimeGrid {

    /** Max consecutive missing buckets that are linearly interpolated; longer gaps stay {@code NaN}. */
    public static final int MAX_INTERPOLATED_GAP = 3;

    private TimeGrid() {
    }

    /** Number of grid buckets spanning {@code [startEpochSec, endEpochSec)} at {@code bucketSeconds}. */
    public static int bucketCount(long startEpochSec, long endEpochSec, int bucketSeconds) {
        if (endEpochSec <= startEpochSec || bucketSeconds <= 0) {
            return 0;
        }
        return (int) ((endEpochSec - startEpochSec + bucketSeconds - 1) / bucketSeconds);
    }

    /**
     * Place {@code values[i]} (sampled at {@code timestampsSec[i]}) onto a regular grid spanning
     * {@code [startEpochSec, endEpochSec)}. Empty buckets are {@code NaN}; short interior gaps are
     * linearly interpolated. The two input arrays must be the same length.
     */
    public static double[] toGrid(long[] timestampsSec, double[] values,
                                  long startEpochSec, long endEpochSec, int bucketSeconds) {
        int n = bucketCount(startEpochSec, endEpochSec, bucketSeconds);
        double[] grid = new double[n];
        java.util.Arrays.fill(grid, Double.NaN);
        if (n == 0) {
            return grid;
        }
        for (int i = 0; i < timestampsSec.length; i++) {
            double v = values[i];
            if (Double.isNaN(v)) {
                continue;
            }
            long offset = timestampsSec[i] - startEpochSec;
            if (offset < 0) {
                continue;
            }
            int idx = (int) (offset / bucketSeconds);
            if (idx >= 0 && idx < n) {
                grid[idx] = v; // last write wins; ClickHouse yields one value per bucket
            }
        }
        interpolateSmallGaps(grid, MAX_INTERPOLATED_GAP);
        return grid;
    }

    /** Linearly fill runs of {@code NaN} no longer than {@code maxGap} that sit between two values. */
    public static void interpolateSmallGaps(double[] grid, int maxGap) {
        int i = 0;
        while (i < grid.length) {
            if (!Double.isNaN(grid[i])) {
                i++;
                continue;
            }
            int gapStart = i;
            while (i < grid.length && Double.isNaN(grid[i])) {
                i++;
            }
            int gapEnd = i; // first non-NaN after the gap, or grid.length
            int gapLen = gapEnd - gapStart;
            if (gapStart > 0 && gapEnd < grid.length && gapLen <= maxGap) {
                double left = grid[gapStart - 1];
                double right = grid[gapEnd];
                for (int k = 0; k < gapLen; k++) {
                    grid[gapStart + k] = left + (right - left) * (k + 1) / (gapLen + 1);
                }
            }
        }
    }

    /** A pair of equal-length, NaN-free, index-aligned series plus how many joint buckets had data. */
    public record Aligned(double[] a, double[] b, int overlapCount) {
    }

    /**
     * Trim two equal-length grids to the index range where both have data, fill any remaining
     * interior gaps so the result is contiguous, and report the joint non-missing count. Returns
     * {@code null} if the overlap is shorter than {@code minPoints} or below {@code minOverlapFraction}
     * of the trimmed span.
     */
    public static Aligned align(double[] a, double[] b, int minPoints, double minOverlapFraction) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("grids must be the same length");
        }
        int first = -1;
        int last = -1;
        int joint = 0;
        for (int i = 0; i < a.length; i++) {
            boolean both = !Double.isNaN(a[i]) && !Double.isNaN(b[i]);
            if (both) {
                if (first < 0) {
                    first = i;
                }
                last = i;
                joint++;
            }
        }
        if (first < 0) {
            return null;
        }
        int span = last - first + 1;
        if (joint < minPoints || (double) joint / span < minOverlapFraction) {
            return null;
        }
        double[] ta = java.util.Arrays.copyOfRange(a, first, last + 1);
        double[] tb = java.util.Arrays.copyOfRange(b, first, last + 1);
        // Fill any remaining (larger) interior gaps so the math sees contiguous arrays. Edges are
        // guaranteed non-NaN because we trimmed to the joint-valid range.
        fillRemaining(ta);
        fillRemaining(tb);
        return new Aligned(ta, tb, joint);
    }

    private static void fillRemaining(double[] s) {
        interpolateSmallGaps(s, s.length); // edges are valid, so every interior gap gets filled
    }
}
