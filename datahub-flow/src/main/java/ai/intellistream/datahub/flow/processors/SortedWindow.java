// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import java.util.Arrays;

/**
 * The values in a sliding window, kept sorted, for a rolling median: adding or removing a value is
 * a binary search and a shift, which for the window sizes flows use beats re-sorting per point.
 */
final class SortedWindow {

    private double[] values = new double[64];
    private int size;

    int size() {
        return size;
    }

    void add(double value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, size * 2);
        }
        int at = insertionPoint(value);
        System.arraycopy(values, at, values, at + 1, size - at);
        values[at] = value;
        size++;
    }

    void remove(double value) {
        int at = Arrays.binarySearch(values, 0, size, value);
        if (at < 0) {
            throw new IllegalStateException(value + " is not in the window");
        }
        System.arraycopy(values, at + 1, values, at, size - at - 1);
        size--;
    }

    /** The median of the values; the mean of the middle two for an even count. */
    double median() {
        if (size == 0) {
            throw new IllegalStateException("The window is empty");
        }
        int mid = size / 2;
        return size % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2;
    }

    /** The median of the values' absolute deviations from {@code center}. */
    double medianAbsoluteDeviation(double center) {
        double[] deviations = new double[size];
        for (int i = 0; i < size; i++) {
            deviations[i] = Math.abs(values[i] - center);
        }
        Arrays.sort(deviations);
        int mid = size / 2;
        return size % 2 == 1 ? deviations[mid] : (deviations[mid - 1] + deviations[mid]) / 2;
    }

    private int insertionPoint(double value) {
        int at = Arrays.binarySearch(values, 0, size, value);
        return at < 0 ? -at - 1 : at;
    }
}
