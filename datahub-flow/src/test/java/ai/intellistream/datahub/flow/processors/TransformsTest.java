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

    // --- scale ------------------------------------------------------------------------------------

    @Test
    void scaleConvertsUnits() {
        h.series(minutes(3), new double[]{0, 100, -40});
        h.pipe("timeseries.scale", "{ \"factor\": 1.8, \"offset\": 32 }", "success");

        assertThat(only("success").v()).containsExactly(32, 212, -40);
        assertThat(only("success").t()).containsExactly(minutes(3));
    }

    // --- smooth -----------------------------------------------------------------------------------

    @Test
    void medianSmoothingRemovesAShortSpikeAndPassesEarlyPointsThrough() {
        // window 3 minutes: points before T0+3min have too little history.
        h.series(minutes(7), new double[]{1, 1, 1, 1, 50, 1, 1});
        h.pipe("timeseries.smooth", "{ \"method\": \"median\", \"window\": \"PT3M\" }", "success");

        // At 4 min the window (1, 4] holds 1, 1, 50 → 1.
        assertThat(only("success").v()).containsExactly(1, 1, 1, 1, 1, 1, 1);
    }

    @Test
    void meanSmoothingUsesATrailingTimeWindowOverIrregularPoints() {
        long[] t = {T0, T0 + 10 * MINUTE, T0 + 11 * MINUTE, T0 + 12 * MINUTE, T0 + 30 * MINUTE};
        h.series(t, new double[]{10, 2, 4, 6, 100});
        h.pipe("timeseries.smooth", "{ \"method\": \"mean\", \"window\": \"PT5M\" }", "success");

        // t=10: window (5,10] holds 2 → 2; t=11: 2,4 → 3; t=12: 2,4,6 → 4; t=30: 100.
        assertThat(only("success").v()).containsExactly(10, 2, 3, 4, 100);
    }

    @Test
    void exponentialSmoothingWeighsByTheGapBetweenPoints() {
        h.series(new long[]{T0, T0 + MINUTE, T0 + 2 * MINUTE}, new double[]{0, 10, 10});
        h.pipe("timeseries.smooth", "{ \"method\": \"exponential\", \"window\": \"PT1M\" }",
                "success");

        double alpha = 1 - Math.exp(-1);
        double s1 = alpha * 10;
        double s2 = s1 + alpha * (10 - s1);
        assertThat(only("success").v()[0]).isEqualTo(0);
        assertThat(only("success").v()[1]).isCloseTo(s1, within(1e-12));
        assertThat(only("success").v()[2]).isCloseTo(s2, within(1e-12));
    }

    // --- despike ----------------------------------------------------------------------------------

    @Test
    void despikeRemovesASpikeAndSendsItOn() {
        h.series(minutes(8), new double[]{10, 11, 10, 11, 10, 90, 11, 10});
        h.pipe("timeseries.despike", "{ \"window\": \"PT4M\" }", "success", "spikes");

        Captured kept = only("success");
        assertThat(kept.v()).containsExactly(10, 11, 10, 11, 10, 11, 10);
        assertThat(kept.attribute("despike.count")).isEqualTo("1");
        Captured spikes = only("spikes");
        assertThat(spikes.t()).containsExactly(T0 + 5 * MINUTE);
        assertThat(spikes.v()).containsExactly(90);
        assertThat(spikes.attribute(Points.EXTERNAL_ID)).isEqualTo("ts1");
    }

    @Test
    void despikeCanReplaceWithTheRollingMedian() {
        h.series(minutes(8), new double[]{10, 11, 10, 11, 10, 90, 11, 10});
        h.pipe("timeseries.despike", "{ \"window\": \"PT4M\", \"action\": \"replace\" }", "success");

        // The window before 5 min, [1, 5), holds 11, 10, 11, 10 → median 10.5.
        assertThat(only("success").v()).containsExactly(10, 11, 10, 11, 10, 10.5, 11, 10);
    }

    @Test
    void despikeLeavesASeriesWithoutSpikesAloneAndEmitsNoSpikesItem() {
        h.series(minutes(6), new double[]{1, 2, 3, 4, 5, 6});
        h.pipe("timeseries.despike", "{ \"window\": \"PT3M\", \"threshold\": 5 }", "success",
                "spikes");

        assertThat(only("success").v()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(only("success").attribute("despike.count")).isEqualTo("0");
        assertThat(h.at("spikes")).isEmpty();
    }

    // --- interpolate ------------------------------------------------------------------------------

    @Test
    void interpolateFillsGapsLinearlyAndNeverExtrapolates() {
        h.series(new long[]{T0, T0 + 4 * MINUTE, T0 + 5 * MINUTE}, new double[]{0, 8, 9});
        h.pipe("timeseries.interpolate", "{ \"interval\": \"PT1M\" }", "success");

        Captured out = only("success");
        assertThat(out.t()).containsExactly(minutes(6));
        assertThat(out.v()).containsExactly(0, 2, 4, 6, 8, 9);
        assertThat(out.attribute("interpolate.count")).isEqualTo("3");
    }

    @Test
    void interpolateCanHoldThePreviousValueAndLeavesLongGapsOpen() {
        h.series(new long[]{T0, T0 + 3 * MINUTE, T0 + 20 * MINUTE}, new double[]{5, 7, 9});
        h.pipe("timeseries.interpolate",
                "{ \"interval\": \"PT1M\", \"method\": \"previous\", \"maxGap\": \"PT10M\" }",
                "success");

        Captured out = only("success");
        assertThat(out.t()).containsExactly(T0, T0 + MINUTE, T0 + 2 * MINUTE, T0 + 3 * MINUTE,
                T0 + 20 * MINUTE);
        assertThat(out.v()).containsExactly(5, 5, 5, 7, 9);
    }

    @Test
    void interpolateIgnoresJitterInTheSampling() {
        // A point a few seconds late, or early, is not a gap.
        h.series(new long[]{T0, T0 + MINUTE + 5_000, T0 + 2 * MINUTE + 10_000},
                new double[]{1, 2, 3});
        h.pipe("timeseries.interpolate", "{ \"interval\": \"PT1M\" }", "success");

        assertThat(only("success").v()).containsExactly(1, 2, 3);
    }

    // --- clip -------------------------------------------------------------------------------------

    @Test
    void clipLimitsValuesAndCountsWhatItChanged() {
        h.series(minutes(4), new double[]{-5, 0, 50, 120});
        h.pipe("timeseries.clip", "{ \"min\": 0, \"max\": 100 }", "success");

        assertThat(only("success").v()).containsExactly(0, 0, 50, 100);
        assertThat(only("success").attribute("clip.count")).isEqualTo("2");
    }

    @Test
    void clipWithOnlyAMaxLeavesLowValues() {
        h.series(minutes(2), new double[]{-1000, 1000});
        h.pipe("timeseries.clip", "{ \"max\": 10 }", "success");

        assertThat(only("success").v()).containsExactly(-1000, 10);
    }

    // --- resample ---------------------------------------------------------------------------------

    @Test
    void resampleAlignsBucketsToTheEpoch() {
        // Points at :07, :20, :52 and 1:05 → buckets :00 and 1:00 for PT1H.
        long hour = 60 * MINUTE;
        long[] t = {T0 + 7 * MINUTE, T0 + 20 * MINUTE, T0 + 52 * MINUTE, T0 + hour + 5 * MINUTE};
        double[] v = {1, 2, 6, 10};
        h.series(t, v);
        h.pipe("timeseries.resample", "{ \"interval\": \"PT1H\", \"aggregate\": \"mean\" }",
                "success");

        assertThat(only("success").t()).containsExactly(T0, T0 + hour);
        assertThat(only("success").v()).containsExactly(3, 10);
    }

    @Test
    void resampleAggregates() {
        long[] t = {T0, T0 + 10_000, T0 + 20_000, T0 + MINUTE};
        double[] v = {4, 1, 7, 2};
        for (String[] c : new String[][]{{"min", "1"}, {"max", "7"}, {"first", "4"}, {"last", "7"},
                {"sum", "12"}, {"count", "3"}}) {
            Harness one = new Harness();
            one.series(t, v);
            one.pipe("timeseries.resample", "{ \"interval\": \"PT1M\", \"aggregate\": \"" + c[0]
                    + "\" }", "success");
            assertThat(one.at("success").getFirst().v()[0]).as(c[0]).isEqualTo(Double.parseDouble(c[1]));
            assertThat(one.at("success").getFirst().v()[1]).as(c[0] + " second bucket")
                    .isEqualTo(c[0].equals("count") ? 1 : 2);
            one.close();
        }
    }

    @Test
    void resampleSortsAnUnorderedBatchFirst() {
        h.series(new long[]{T0 + MINUTE, T0}, new double[]{2, 1});
        h.pipe("timeseries.resample", "{ \"interval\": \"PT1M\", \"aggregate\": \"first\" }",
                "success");

        assertThat(only("success").t()).containsExactly(T0, T0 + MINUTE);
        assertThat(only("success").v()).containsExactly(1, 2);
    }

    // --- filter.range -----------------------------------------------------------------------------

    @Test
    void filterRangeSplitsAndKeepsAttributes() {
        h.series(minutes(4), new double[]{1, 5, 10, 11});
        h.pipe("timeseries.filter.range", "{ \"min\": 5, \"max\": 10 }", "inRange", "outOfRange");

        assertThat(only("inRange").v()).containsExactly(5, 10);
        assertThat(only("outOfRange").v()).containsExactly(1, 11);
        assertThat(only("outOfRange").attribute(Points.EXTERNAL_ID)).isEqualTo("ts1");
    }

    @Test
    void filterRangeExclusiveAndAnEmptySideIsNotEmitted() {
        h.series(minutes(2), new double[]{5, 10});
        h.pipe("timeseries.filter.range", "{ \"min\": 5, \"max\": 10, \"inclusive\": false }",
                "inRange", "outOfRange");

        assertThat(h.at("inRange")).isEmpty();
        assertThat(only("outOfRange").v()).containsExactly(5, 10);
    }

    // --- threshold --------------------------------------------------------------------------------

    @Test
    void thresholdEmitsAPeriodAsAnActiveAndAClearedRow() {
        h.series(minutes(6), new double[]{1, 5, 9, 6, 2, 1});
        h.pipe("timeseries.threshold", "{ \"threshold\": 4 }", "alarms", "success");

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
        h.pipe("timeseries.threshold",
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
        h.pipe("timeseries.threshold", "{ \"threshold\": 10 }", "alarms", "success");

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
        h.pipe("timeseries.threshold", "{ \"threshold\": 4, \"minDuration\": \"PT5M\" }", "alarms",
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
        next.pipe("timeseries.threshold", "{ \"threshold\": 4, \"minDuration\": \"PT5M\" }",
                "alarms", "success");
        List<EventRow> run2 = next.at("alarms").getFirst().rows();
        next.close();

        // Short (3 minutes), but already opened by run 1: closed, under the same external id.
        assertThat(run2).extracting(EventRow::status).containsExactly("cleared");
        assertThat(run2.getFirst().externalId()).isEqualTo(run1.getFirst().externalId());
    }

    // --- route.on.attribute -----------------------------------------------------------------------

    @Test
    void routeOnAttributeSendsItemsByValue() {
        Map<String, String> pump = Map.of(Points.EXTERNAL_ID, "pump_1");
        Map<String, String> fan = Map.of(Points.EXTERNAL_ID, "fan_1");
        Map<String, String> other = Map.of(Points.EXTERNAL_ID, "valve_1");
        h.series(pump, minutes(1), new double[]{1});
        h.series(fan, minutes(1), new double[]{2});
        h.series(other, minutes(1), new double[]{3});
        h.pipe("route.on.attribute",
                "{ \"attribute\": \"timeseries.externalId\", \"pumps\": \"pump_1\", \"fans\": \"fan_1\" }",
                "pumps", "fans", "unmatched");

        assertThat(only("pumps").v()).containsExactly(1);
        assertThat(only("fans").v()).containsExactly(2);
        assertThat(only("unmatched").v()).containsExactly(3);
    }
}
