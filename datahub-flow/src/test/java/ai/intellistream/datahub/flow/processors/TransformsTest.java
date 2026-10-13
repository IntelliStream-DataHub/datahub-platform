// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.processors.Harness.Captured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static ai.intellistream.datahub.flow.processors.Harness.MINUTE;
import static ai.intellistream.datahub.flow.processors.Harness.T0;
import static ai.intellistream.datahub.flow.processors.Harness.minutes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Every transform against known inputs, run in the engine. */
class TransformsTest {

    private final Harness h = new Harness();

    @AfterEach
    void released() {
        h.close();
    }

    private Captured only(String collector) {
        assertThat(h.at(collector)).hasSize(1);
        return h.at(collector).getFirst();
    }

    // --- clip -------------------------------------------------------------------------------------

    @Test
    void clipLimitsValuesAndCountsWhatItChanged() {
        h.series(minutes(4), new double[]{-5, 0, 50, 120});
        h.pipe("clip", "{ \"min\": 0, \"max\": 100 }", "success");

        assertThat(only("success").v()).containsExactly(0, 0, 50, 100);
        assertThat(only("success").attribute("clip.count")).isEqualTo("2");
    }

    @Test
    void clipWithOnlyAMaxLeavesLowValues() {
        h.series(minutes(2), new double[]{-1000, 1000});
        h.pipe("clip", "{ \"max\": 10 }", "success");

        assertThat(only("success").v()).containsExactly(-1000, 10);
    }

    // --- threshold --------------------------------------------------------------------------------

    @Test
    void thresholdEmitsAPeriodAsAnActiveAndAClearedRow() {
        h.series(minutes(6), new double[]{1, 5, 9, 6, 2, 1});
        h.pipe("threshold", "{ \"threshold\": 4 }", "alarms", "success");

        List<EventRow> rows = only("alarms").rows();
        String id = "ts1:above:" + Instant.ofEpochMilli(T0 + MINUTE);
        assertThat(rows).extracting(EventRow::externalId).containsOnly(id);
        assertThat(rows).extracting(EventRow::status).containsExactly("active", "cleared");
        assertThat(rows.get(0).eventTime()).isEqualTo(Instant.ofEpochMilli(T0 + MINUTE));
        assertThat(rows.get(1).eventTime()).isEqualTo(Instant.ofEpochMilli(T0 + 4 * MINUTE));
        assertThat(rows.get(1).metadata()).containsEntry("peak", "9.0")
                .containsEntry("threshold", "4.0").containsEntry("duration", "PT3M");
        assertThat(only("success").v()).containsExactly(1, 5, 9, 6, 2, 1);
    }

    @Test
    void thresholdBelowAndMinDuration() {
        h.series(minutes(8), new double[]{5, -1, 5, -2, -3, -1, 5, 5});
        h.pipe("threshold",
                "{ \"threshold\": 0, \"direction\": \"below\", \"minDuration\": \"PT2M\" }",
                "alarms", "success");

        // The one-minute dip at 1 min is too short; the one from 3 to 6 min is kept.
        List<EventRow> rows = only("alarms").rows();
        assertThat(rows).extracting(EventRow::status).containsExactly("active", "cleared");
        assertThat(rows.getFirst().externalId()).endsWith(":below:" + Instant.ofEpochMilli(T0 + 3 * MINUTE));
        assertThat(rows.getFirst().metadata()).containsEntry("trough", "-3.0");
    }

    @Test
    void thresholdEmitsNoAlarmsItemWhenNothingCrossed() {
        h.series(minutes(3), new double[]{1, 2, 3});
        h.pipe("threshold", "{ \"threshold\": 10 }", "alarms", "success");

        assertThat(h.at("alarms")).isEmpty();
        assertThat(h.at("success")).hasSize(1);
    }

    @Test
    void aPeriodStraddlingTwoWindowsJoinsUpAcrossRuns() {
        // Run 1, window 10:00–11:00: the period starts at 10:58 and is still going at 10:59.
        long w1 = T0;
        Map<String, String> first = Map.of(Points.EXTERNAL_ID, "ts1",
                Points.WINDOW_START, Instant.ofEpochMilli(w1).toString(),
                Points.WINDOW_END, Instant.ofEpochMilli(w1 + 60 * MINUTE).toString());
        h.series(first, new long[]{w1 + 57 * MINUTE, w1 + 58 * MINUTE, w1 + 59 * MINUTE},
                new double[]{1, 9, 9});
        h.pipe("threshold", "{ \"threshold\": 4, \"minDuration\": \"PT5M\" }", "alarms",
                "success");
        List<EventRow> run1 = only("alarms").rows();
        assertThat(run1).extracting(EventRow::status).containsExactly("active");

        // Run 2, window 11:00–12:00 with a lookback back to 10:57: it ends at 11:01.
        Harness next = new Harness();
        long w2 = w1 + 60 * MINUTE;
        Map<String, String> second = Map.of(Points.EXTERNAL_ID, "ts1",
                Points.WINDOW_START, Instant.ofEpochMilli(w2).toString(),
                Points.WINDOW_END, Instant.ofEpochMilli(w2 + 60 * MINUTE).toString());
        next.series(second, new long[]{w1 + 57 * MINUTE, w1 + 58 * MINUTE, w1 + 59 * MINUTE,
                w2, w2 + MINUTE}, new double[]{1, 9, 9, 9, 2});
        next.pipe("threshold", "{ \"threshold\": 4, \"minDuration\": \"PT5M\" }",
                "alarms", "success");
        List<EventRow> run2 = next.at("alarms").getFirst().rows();
        next.close();

        // Short (3 minutes), but already opened by run 1: closed, under the same external id.
        assertThat(run2).extracting(EventRow::status).containsExactly("cleared");
        assertThat(run2.getFirst().externalId()).isEqualTo(run1.getFirst().externalId());
    }
}
