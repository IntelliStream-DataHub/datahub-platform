// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

/**
 * Magnitude-squared coherence via Welch's method:
 *
 * <pre>C_xy(f) = |S_xy(f)|² / (S_xx(f)·S_yy(f))</pre>
 *
 * estimated by averaging the cross- and auto-spectra over overlapping, Hann-windowed FFT segments.
 * Answers "on which timescale are these two series related?" — each FFT bin maps to a period
 * ({@code segmentLen·bucketSeconds / k}).
 *
 * <p>Averaging is essential: single-segment coherence is identically 1. Segment length is a power of
 * two (FFT requirement) chosen so there are several averaged segments, which is why the longest
 * resolvable period is the segment length (~window/8), not the full window.
 */
public final class Coherence {

    private Coherence() {
    }

    /** Minimum averaged segments for a usable coherence estimate. */
    public static final int MIN_SEGMENTS = 4;

    /** Maximum number of spectrum points returned (downsampled for plotting). */
    public static final int MAX_SPECTRUM_POINTS = 128;

    /**
     * @param frequency        cycles per second at each returned bin
     * @param periodSeconds    period (1/frequency) at each returned bin
     * @param coherence        magnitude-squared coherence in [0, 1] at each returned bin
     * @param peakCoherence    maximum coherence over the spectrum
     * @param peakPeriodSeconds period at the peak
     * @param segments         number of averaged Welch segments
     * @param segmentLength     FFT segment length (buckets)
     * @param minPeriodSeconds  shortest resolvable period (Nyquist, 2·bucketSeconds)
     * @param maxPeriodSeconds  longest resolvable period (segmentLength·bucketSeconds)
     */
    public record Result(double[] frequency, double[] periodSeconds, double[] coherence,
                         double peakCoherence, double peakPeriodSeconds, int segments,
                         int segmentLength, double minPeriodSeconds, double maxPeriodSeconds) {
    }

    /**
     * @param requestedSegmentLength optional segment length; floored to a power of two when given,
     *                               otherwise derived as the power of two near {@code n/8}.
     * @return the coherence estimate, or {@code null} if the overlap is too short to average.
     */
    public static Result welch(double[] x, double[] y, int bucketSeconds, Integer requestedSegmentLength) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("series must be the same length");
        }
        int n = x.length;
        int segLen = requestedSegmentLength != null
                ? pow2Floor(requestedSegmentLength)
                : pow2Floor(n / 8);
        if (segLen < 8) {
            segLen = 8;
        }
        if (segLen > pow2Floor(n)) {
            segLen = pow2Floor(n);
        }
        if (segLen < 8 || n < segLen) {
            return null;
        }
        int step = segLen / 2;
        int segments = (n - segLen) / step + 1;
        if (segments < MIN_SEGMENTS) {
            return null;
        }

        int half = segLen / 2;
        double[] window = hann(segLen);
        double[] pxx = new double[half + 1];
        double[] pyy = new double[half + 1];
        double[] pxyRe = new double[half + 1];
        double[] pxyIm = new double[half + 1];

        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
        for (int s = 0; s < segments; s++) {
            int off = s * step;
            double[] xs = windowed(x, off, segLen, window);
            double[] ys = windowed(y, off, segLen, window);
            Complex[] xf = fft.transform(xs, TransformType.FORWARD);
            Complex[] yf = fft.transform(ys, TransformType.FORWARD);
            for (int k = 0; k <= half; k++) {
                double xr = xf[k].getReal();
                double xi = xf[k].getImaginary();
                double yr = yf[k].getReal();
                double yi = yf[k].getImaginary();
                pxx[k] += xr * xr + xi * xi;
                pyy[k] += yr * yr + yi * yi;
                // X · conj(Y)
                pxyRe[k] += xr * yr + xi * yi;
                pxyIm[k] += xi * yr - xr * yi;
            }
        }

        // Bins 1..half (skip DC). Build full spectrum, then downsample.
        int bins = half; // k = 1..half
        double[] freqAll = new double[bins];
        double[] periodAll = new double[bins];
        double[] cohAll = new double[bins];
        double peak = -1;
        double peakPeriod = 0;
        for (int k = 1; k <= half; k++) {
            double denom = pxx[k] * pyy[k];
            double coh = denom == 0 ? 0 : (pxyRe[k] * pxyRe[k] + pxyIm[k] * pxyIm[k]) / denom;
            if (coh > 1) {
                coh = 1; // guard tiny numeric overshoot
            }
            double freq = (double) k / ((double) segLen * bucketSeconds);
            double period = (double) segLen * bucketSeconds / k;
            int i = k - 1;
            freqAll[i] = freq;
            periodAll[i] = period;
            cohAll[i] = coh;
            if (coh > peak) {
                peak = coh;
                peakPeriod = period;
            }
        }

        Downsampled ds = downsample(freqAll, periodAll, cohAll, MAX_SPECTRUM_POINTS);
        double minPeriod = 2.0 * bucketSeconds;
        double maxPeriod = (double) segLen * bucketSeconds;
        return new Result(ds.freq, ds.period, ds.coh, peak, peakPeriod, segments, segLen, minPeriod, maxPeriod);
    }

    private record Downsampled(double[] freq, double[] period, double[] coh) {
    }

    private static Downsampled downsample(double[] freq, double[] period, double[] coh, int maxPoints) {
        int n = freq.length;
        if (n <= maxPoints) {
            return new Downsampled(freq, period, coh);
        }
        int stride = (int) Math.ceil((double) n / maxPoints);
        int outN = (n + stride - 1) / stride;
        double[] f = new double[outN];
        double[] p = new double[outN];
        double[] c = new double[outN];
        for (int i = 0, j = 0; i < n && j < outN; i += stride, j++) {
            // Within each stride keep the max-coherence bin so peaks survive downsampling.
            int end = Math.min(n, i + stride);
            int best = i;
            for (int k = i; k < end; k++) {
                if (coh[k] > coh[best]) {
                    best = k;
                }
            }
            f[j] = freq[best];
            p[j] = period[best];
            c[j] = coh[best];
        }
        return new Downsampled(f, p, c);
    }

    private static double[] windowed(double[] src, int off, int len, double[] window) {
        double[] out = new double[len];
        double mean = 0;
        for (int i = 0; i < len; i++) {
            mean += src[off + i];
        }
        mean /= len;
        for (int i = 0; i < len; i++) {
            out[i] = (src[off + i] - mean) * window[i];
        }
        return out;
    }

    private static double[] hann(int len) {
        double[] w = new double[len];
        for (int i = 0; i < len; i++) {
            w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (len - 1));
        }
        return w;
    }

    /** Largest power of two ≤ v (at least 1). */
    static int pow2Floor(int v) {
        if (v < 1) {
            return 1;
        }
        return Integer.highestOneBit(v);
    }
}
