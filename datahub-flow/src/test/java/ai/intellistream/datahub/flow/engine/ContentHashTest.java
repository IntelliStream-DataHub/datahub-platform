// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.EventsContent;
import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content hash is a contract: a hash recorded today must equal the hash of the same data read
 * next year, or "has the input changed since?" stops meaning anything. So its values are pinned
 * here, and changing the canonical form must fail this test.
 */
class ContentHashTest {

    private static TimeseriesContent series(long[] timestamps, double[] values) {
        TimeseriesContent.Builder b = new TimeseriesContent.Builder(timestamps.length, bytes -> { });
        for (int i = 0; i < timestamps.length; i++) {
            b.add(timestamps[i], values[i]);
        }
        return b.build();
    }

    @Test
    void aTimeseriesHashIsPinned() {
        TimeseriesContent ts = series(new long[]{1_000, 2_000, 3_000}, new double[]{1.5, -2.25, 4});
        assertThat(ContentHash.of(ts)).isEqualTo(PINNED_TIMESERIES);
    }

    @Test
    void anEventsHashIsPinned() {
        EventsContent events = EventsContent.of(List.of(
                new EventRow("ts1:high", Instant.ofEpochMilli(1_000), "active", Map.of("peak", "41.5")),
                new EventRow("ts1:high", Instant.ofEpochMilli(5_000), "cleared", Map.of())));
        assertThat(ContentHash.of(events)).isEqualTo(PINNED_EVENTS);
    }

    @Test
    void rowOrderDoesNotChangeTheHash() {
        TimeseriesContent sorted = series(new long[]{1_000, 2_000, 3_000}, new double[]{1.5, -2.25, 4});
        TimeseriesContent shuffled = series(new long[]{3_000, 1_000, 2_000}, new double[]{4, 1.5, -2.25});
        assertThat(ContentHash.of(shuffled)).isEqualTo(ContentHash.of(sorted));

        EventRow a = new EventRow("a", Instant.ofEpochMilli(1), "open", Map.of("x", "1", "y", "2"));
        EventRow b = new EventRow("b", Instant.ofEpochMilli(1), "open", Map.of());
        assertThat(ContentHash.of(EventsContent.of(List.of(b, a))))
                .isEqualTo(ContentHash.of(EventsContent.of(List.of(a, b))));
    }

    @Test
    void anyChangeToTheDataChangesTheHash() {
        long base = ContentHash.of(series(new long[]{1_000, 2_000}, new double[]{1, 2}));
        assertThat(ContentHash.of(series(new long[]{1_000, 2_000}, new double[]{1, 2.0000001})))
                .isNotEqualTo(base);
        assertThat(ContentHash.of(series(new long[]{1_000, 2_001}, new double[]{1, 2})))
                .isNotEqualTo(base);
        assertThat(ContentHash.of(series(new long[]{1_000}, new double[]{1})))
                .isNotEqualTo(base);
    }

    @Test
    void emptyContentOfDifferentKindsDoesNotCollide() {
        assertThat(ContentHash.of(series(new long[0], new double[0])))
                .isNotEqualTo(ContentHash.of(EventsContent.of(List.of())));
    }

    @Test
    void everyNanIsTheSameNan() {
        double otherNan = Double.longBitsToDouble(0x7ff8000000000001L);
        assertThat(ContentHash.of(series(new long[]{1}, new double[]{otherNan})))
                .isEqualTo(ContentHash.of(series(new long[]{1}, new double[]{Double.NaN})));
    }

    private static final long PINNED_TIMESERIES = 2160804835888317062L;
    private static final long PINNED_EVENTS = -7453827369648405393L;
}
