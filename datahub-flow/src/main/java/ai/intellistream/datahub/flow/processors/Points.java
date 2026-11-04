// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.api.TimeseriesContent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/**
 * A timeseries item's points as two arrays in timestamp order, which is how every built-in
 * processor works on them: a batch keeps rows in the order they were added, so a processor that
 * needs order gets it here rather than assuming it.
 *
 * <p>Also the attributes every timeseries item carries ({@link #EXTERNAL_ID}, {@link #WINDOW_START},
 * {@link #WINDOW_END}), set by the source and read by the sinks and the threshold.
 */
final class Points {

    /** The series an item holds. */
    static final String EXTERNAL_ID = "timeseries.externalId";
    /** The run's window the item belongs to, as ISO instants; points before it are history. */
    static final String WINDOW_START = "window.start";
    static final String WINDOW_END = "window.end";

    final long[] t;
    final double[] v;
    final int n;

    Points(long[] t, double[] v, int n) {
        this.t = t;
        this.v = v;
        this.n = n;
    }

    /** The content's points, sorted by timestamp; rows with the same timestamp keep their order. */
    static Points of(TimeseriesContent content) {
        int n = content.rowCount();
        long[] t = new long[n];
        double[] v = new double[n];
        boolean sorted = true;
        for (int i = 0; i < n; i++) {
            t[i] = content.timestamp(i);
            v[i] = content.value(i);
            if (i > 0 && t[i] < t[i - 1]) {
                sorted = false;
            }
        }
        return sorted ? new Points(t, v, n) : sortedCopy(t, v, n);
    }

    /** Sorts by timestamp, stably, so the last of several equal timestamps stays last. */
    static Points sortedCopy(long[] t, double[] v, int n) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(t[a], t[b]));
        long[] st = new long[n];
        double[] sv = new double[n];
        for (int i = 0; i < n; i++) {
            st[i] = t[order[i]];
            sv[i] = v[order[i]];
        }
        return new Points(st, sv, n);
    }

    TimeseriesContent write(ProcessSession session) {
        TimeseriesContent.Builder builder = session.newTimeseries(n);
        for (int i = 0; i < n; i++) {
            builder.add(t[i], v[i]);
        }
        return builder.build();
    }

    /**
     * The window an item belongs to, from its {@link #WINDOW_START} and {@link #WINDOW_END}
     * attributes, or null when it has none.
     */
    static TimeWindow window(Item item) {
        String start = item.attribute(WINDOW_START);
        String end = item.attribute(WINDOW_END);
        if (start == null || end == null) {
            return null;
        }
        try {
            return new TimeWindow(Instant.parse(start), Instant.parse(end));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ProcessException(item + " has an unreadable window " + start + " .. " + end, e);
        }
    }
}
