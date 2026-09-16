// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Per-request round-trip times, kept in full so the percentiles are exact rather than bucketed. */
final class Latencies {

    private final List<Long> nanos = Collections.synchronizedList(new ArrayList<>());

    void record(long elapsedNanos) {
        nanos.add(elapsedNanos);
    }

    int count() {
        return nanos.size();
    }

    /** The p'th percentile in milliseconds, nearest-rank. Zero when nothing was recorded. */
    double percentileMillis(double p) {
        List<Long> sorted;
        synchronized (nanos) {
            if (nanos.isEmpty()) {
                return 0;
            }
            sorted = new ArrayList<>(nanos);
        }
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1))) / 1_000_000.0;
    }

    double meanMillis() {
        synchronized (nanos) {
            return nanos.isEmpty() ? 0
                    : nanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        }
    }
}
